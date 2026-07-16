(function() {
    const TAG = "[FBAutomation]";
    
    // 强制修正移动端视口，解决留白问题
    function fixViewport() {
        let meta = document.querySelector('meta[name="viewport"]');
        if (!meta) {
            meta = document.createElement('meta');
            meta.name = 'viewport';
            document.getElementsByTagName('head')[0].appendChild(meta);
        }
        meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
        document.body.style.width = '100%';
        document.documentElement.style.width = '100%';
    }
    fixViewport();

    function log(msg) {
        console.log(TAG + " " + msg);
        if (window.AndroidBridge) {
            window.AndroidBridge.log(msg);
        }
    }

    function sleep(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }

    async function waitForElement(selector, timeout = 10000) {
        const start = Date.now();
        while (Date.now() - start < timeout) {
            const el = document.querySelector(selector);
            if (el) return el;
            await sleep(500);
        }
        return null;
    }

    // 寻找分享按钮的逻辑（针对 m.facebook.com）
    async function startAutomation() {
        log("开始执行自动化分享脚本...");
        
        let shareButton = null;
        let scrollAttempts = 0;
        const maxScrolls = 10;

        while (!shareButton && scrollAttempts < maxScrolls) {
            log(`正在寻找分享按钮 (尝试第 ${scrollAttempts + 1} 次)...`);
            
            // 1. 寻找分享按钮
            const shareButtons = Array.from(document.querySelectorAll('div[role="button"], i, span')).filter(el => {
                const label = (el.getAttribute('aria-label') || el.innerText || "").trim();
                return label === "分享" || label === "Share" || label.includes("分享") || label.includes("Share");
            });

            if (shareButtons.length > 0) {
                // 找到按钮了，检查它是否在视图中
                for (let btn of shareButtons) {
                    const rect = btn.getBoundingClientRect();
                    if (rect.top > 0 && rect.bottom < window.innerHeight) {
                        shareButton = btn;
                        break;
                    }
                }
            }

            if (!shareButton) {
                log("未在当前视图发现分享按钮，尝试向下滚动...");
                window.scrollBy(0, 500);
                scrollAttempts++;
                await sleep(1500); // 等待滚动和加载
            }
        }

        if (!shareButton) {
            log("经过多次尝试，仍未发现任何分享按钮，请检查页面内容。");
            return;
        }

        log("锁定分享按钮，准备点击...");
        shareButton.click();
        await sleep(2000);

        // 2. 寻找“分享到小组”选项
        log("寻找 '分享到小组' 选项...");
        const menuItems = Array.from(document.querySelectorAll('span, div')).filter(el => {
            return el.innerText && (el.innerText.includes("分享到小组") || el.innerText.includes("Share to a group"));
        });

        if (menuItems.length > 0) {
            menuItems[0].click();
            await sleep(3000);
        } else {
            log("未找到 '分享到小组' 菜单项。");
            return;
        }

        // 3. 遍历小组并发布 (由于页面动态加载，这里仅演示逻辑)
        log("正在寻找可用的群组列表...");
        // 实际开发中需要更复杂的循环逻辑来处理每一个小组
        
        log("脚本执行完毕。");
        if (window.AndroidBridge) {
            window.AndroidBridge.onComplete();
        }
    }

    // 暴露接口给原生
    window.runFBAutomation = startAutomation;
    log("自动化脚本已注入就绪。");
})();
