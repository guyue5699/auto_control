(function() {
    const TAG = "[FBAutomation]";
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
        
        // 1. 寻找分享按钮
        // 注意：FB 类名混淆严重，通常通过 aria-label 或内容识别
        const shareButtons = Array.from(document.querySelectorAll('div[role="button"], i')).filter(el => {
            const label = el.getAttribute('aria-label') || el.innerText || "";
            return label.includes("分享") || label.includes("Share");
        });

        if (shareButtons.length === 0) {
            log("未发现任何分享按钮，请确保已进入个人主页并加载了帖子。");
            return;
        }

        log(`发现 ${shareButtons.length} 个可能的分享按钮，准备处理第一个...`);
        shareButtons[0].click();
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
