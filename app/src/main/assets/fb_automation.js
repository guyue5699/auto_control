(function() {
    const TAG = "[FBAutomation]";
    
    // 强制修正移动端布局和视口
    function forceLayoutFix() {
        if (document.getElementById('automation-style')) return;

        const style = document.createElement('style');
        style.id = 'automation-style';
        style.innerHTML = `
            /* 1. 强制根容器全屏 */
            html, body { 
                width: 100vw !important; 
                min-width: 100vw !important;
                max-width: 100vw !important;
                overflow-x: hidden !important; 
                margin: 0 !important; 
                padding: 0 !important; 
                background: #fff !important;
                position: relative !important;
            }
            
            /* 2. 强制所有疑似容器的元素宽度撑满 */
            #root, #viewport, [id^="mount"], ._55lr, ._5qx2, ._2v9s, ._4g33, ._52we, ._4-u2, ._4-u8 { 
                width: 100vw !important; 
                max-width: 100vw !important;
                min-width: 100vw !important;
                margin-left: 0 !important;
                margin-right: 0 !important;
                padding-left: 0 !important;
                padding-right: 0 !important;
                box-sizing: border-box !important;
                left: 0 !important;
                right: 0 !important;
            }

            /* 3. 移除所有可能导致右侧留白的绝对定位偏置 */
            * {
                max-width: 100vw !important;
                box-sizing: border-box !important;
            }

            /* 4. 隐藏干扰元素 */
            div[role="banner"], ._55wr, ._7om2, #header, #footer, ._6084 { 
                display: none !important; 
            }

            /* 5. 修复 FB 移动版的 flex 布局 */
            ._4g34 { width: auto !important; flex: 1 !important; }
        `;
        document.head.appendChild(style);
        
        // 动态强刷 viewport
        let meta = document.querySelector('meta[name="viewport"]');
        if (!meta) {
            meta = document.createElement('meta');
            meta.name = 'viewport';
            document.head.appendChild(meta);
        }
        meta.content = 'width=device-width, initial-scale=1.0, minimum-scale=1.0, maximum-scale=1.0, user-scalable=no, shrink-to-fit=no';
        
        log("布局强制修正已应用 (100vw 锁定)");
    }

    // 持续监控布局，防止 SPA 路由跳转后样式丢失
    function startLayoutMonitor() {
        forceLayoutFix();
        const observer = new MutationObserver(() => {
            forceLayoutFix();
        });
        observer.observe(document.documentElement, { childList: true, subtree: true });
    }
    startLayoutMonitor();

    function log(msg) {
        console.log(TAG + " " + msg);
        if (window.AndroidBridge) {
            window.AndroidBridge.log(msg);
        }
    }

    function sleep(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }

    // 寻找分享按钮的核心逻辑
    async function findShareButton() {
        log("开始深度扫描分享按钮...");
        
        // 排除项：避免点到“创建帖子”、“发表新鲜事”等干扰按钮
        const blacklist = ["新鲜事", "mind", "post", "发布", "撰写", "write", "status", "更新状态"];
        const shareKeywords = ["分享", "share", "转发", "forward", "repost"];

        // 优先方案：结构化定位（寻找互动的三个按钮组）
        // 针对没有文字，只有图标的情况。寻找包含 3 个子按钮的横向容器
        const actionBars = Array.from(document.querySelectorAll('div')).filter(el => {
            const childButtons = el.querySelectorAll('div[role="button"], a[role="button"]');
            // FB 移动版互动条通常有 3 个按钮（赞、评、转）
            return childButtons.length >= 3 && childButtons.length <= 5; 
        });

        if (actionBars.length > 0) {
            for (let bar of actionBars) {
                const buttons = bar.querySelectorAll('div[role="button"], a[role="button"]');
                // 最后一个或倒数第二个通常是分享
                const shareBtn = buttons[buttons.length - 1]; 
                if (shareBtn) {
                    const text = (shareBtn.innerText || "").toLowerCase();
                    const aria = (shareBtn.getAttribute('aria-label') || "").toLowerCase();
                    
                    // 检查是否在黑名单中
                    const isInvalid = blacklist.some(kw => text.includes(kw) || aria.includes(kw));
                    if (isInvalid) continue;

                    const rect = shareBtn.getBoundingClientRect();
                    if (rect.height > 0 && rect.top > 100) { // 避开顶部发帖区
                        log("方案：锁定互动条末尾按钮");
                        return shareBtn;
                    }
                }
            }
        }

        // 备选方案：寻找包含特定文本或属性的按钮
        const allButtons = Array.from(document.querySelectorAll('div[role="button"], a[role="button"], span[role="button"]'));
        for (let btn of allButtons) {
            const text = (btn.innerText || "").toLowerCase();
            const aria = (btn.getAttribute('aria-label') || "").toLowerCase();
            const title = (btn.getAttribute('title') || "").toLowerCase();
            
            // 必须包含分享关键字，且不能包含黑名单关键字
            const hasShareKw = shareKeywords.some(kw => text.includes(kw) || aria.includes(kw) || title.includes(kw));
            const hasBlacklistKw = blacklist.some(kw => text.includes(kw) || aria.includes(kw) || title.includes(kw));
            
            if (hasShareKw && !hasBlacklistKw) {
                const rect = btn.getBoundingClientRect();
                // 避开屏幕顶部的发帖框（通常在 y < 150 的位置）
                if (rect.height > 0 && rect.top > 150) {
                    log("方案 A 命中: " + (text || aria || "icon-only"));
                    return btn;
                }
            }
        }

        return null;
    }

    async function startAutomation() {
        log("🚀 自动化引擎启动...");
        
        let shareButton = null;
        let scrollAttempts = 0;
        const maxScrolls = 15; // 增加尝试次数，适应长页面

        while (!shareButton && scrollAttempts < maxScrolls) {
            log(`全量扫描页面元素 (尝试第 ${scrollAttempts + 1}/${maxScrolls})...`);
            
            shareButton = await findShareButton();

            if (!shareButton) {
                log("未在当前视图发现分享按钮，尝试向下滚动...");
                // 每次滚动半屏，确保不会跳过帖子
                window.scrollBy({
                    top: window.innerHeight * 0.5,
                    behavior: 'smooth'
                });
                scrollAttempts++;
                await sleep(2500); // 等待渲染
            }
        }

        if (!shareButton) {
            log("❌ 经过多次尝试，仍未发现任何分享按钮，请检查页面内容。");
            return;
        }

        log("🎯 锁定目标！将其滚动至视图中心...");
        shareButton.scrollIntoView({ behavior: 'smooth', block: 'center' });
        await sleep(1000);

        log("🖱️ 执行物理模拟点击...");
        // 兼容性处理：优先使用 dispatchEvent
        try {
            const coords = shareButton.getBoundingClientRect();
            const x = coords.left + coords.width / 2;
            const y = coords.top + coords.height / 2;
            
            // 触发点击
            shareButton.click();
            
            // 兜底方案：派发触摸事件
            const touchEvent = new MouseEvent('click', {
                view: window,
                bubbles: true,
                cancelable: true,
                clientX: x,
                clientY: y
            });
            shareButton.dispatchEvent(touchEvent);
        } catch (e) {
            shareButton.click();
        }
        
        await sleep(2500);

        // 寻找“分享到小组”
        log("🔎 寻找转发目标菜单...");
        const targetTexts = ["分享到小组", "share to a group", "转发到小组", "在小组中分享", "share in a group"];
        
        let menuClicked = false;
        const allSpans = Array.from(document.querySelectorAll('span, div, a, b'));
        for (let el of allSpans) {
            const text = (el.innerText || "").toLowerCase();
            if (targetTexts.some(kw => text.includes(kw))) {
                log("✅ 找到菜单项: " + text);
                el.click();
                menuClicked = true;
                break;
            }
        }

        if (menuClicked) {
            log("🎉 已进入小组选择界面，请选择目标小组。");
            await sleep(3000);
        } else {
            log("⚠️ 未找到转发菜单，可能菜单未弹出或 UI 发生变化。");
        }

        if (window.AndroidBridge) {
            window.AndroidBridge.onComplete();
        }
    }

    window.runFBAutomation = startAutomation;
    log("自动化引擎已就绪。");
})();
