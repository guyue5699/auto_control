# 自动化群控 - Android 项目运行指南

本项目是一个基于 Android 的自动化工具，目前支持通过 Chrome 浏览器自动分享 Facebook 帖子到指定群组。

## 1. 环境准备

在启动项目之前，请确保你的开发环境已配置好：

- **Android Studio**: 建议使用最新版本 (Hedgehog 或更高版本)。
- **JDK**: 项目配置使用的是 Java 1.8 (Java 8)。
- **Android 设备/模拟器**: 
    - 建议使用真机以获得最佳的无障碍服务测试效果。
    - 确保设备上已安装 **Google Chrome** 浏览器。
    - 确保 Chrome 浏览器中已登录你的 **Facebook** 账号。

## 2. 启动步骤

1. **导入项目**:
   - 打开 Android Studio。
   - 选择 `Open`，然后定位到项目根目录 `d:\code\facebook`。
   - 等待 Gradle 同步完成（这可能需要几分钟，取决于你的网络状况）。

2. **编译与安装**:
   - 连接你的 Android 手机并开启“USB 调试”。
   - 在 Android Studio 顶部的工具栏中选择你的设备。
   - 点击绿色的 `Run` (运行) 按钮。

3. **配置无障碍权限 (核心步骤)**:
   - 应用安装并打开后，你会看到首页。
   - 点击顶部的红色提示框，或者手动前往手机的：`设置 -> 辅助功能 (Accessibility) -> 已安装的服务 -> 自动化群控`。
   - 将开关切换为 **开启**。

## 3. 功能测试 (Facebook 自动分享)

1. 在首页点击 **“Facebook 自动分享”** 卡片。
2. 点击右下角的 **“+”** 号按钮。
3. 输入以下信息：
   - **Facebook 帖子链接**: 你想要分享的帖子 URL。
   - **附带文字内容**: (可选) 分享时想说的文字。
   - **目标群组名称**: 输入你想分享到的群组名称（确保你已加入这些群组）。多个群组请用英文逗号 `,` 分隔。
4. 点击 **“保存任务”**。
5. 在任务列表中，找到刚才创建的任务，点击右侧的 **“播放/开始”** 图标。
6. **观察**: 手机会自动切换到 Chrome 浏览器，并开始自动执行搜索和发布流程。

## 4. 注意事项

- **UI 适配**: 自动化逻辑依赖于 Facebook 网页版的文案（如“发帖”、“写点什么”）。如果你的手机语言不是中文或英文，可能需要调整 `FBAutomationService.kt` 中的识别文案。
- **Chrome 版本**: 请保持 Chrome 浏览器为较新版本。
- **网络**: 确保手机网络能够正常访问 Facebook。

## 5. 项目结构

- `app/src/main/kotlin/com/example/fbsharer/ui/screens/`: UI 界面代码。
- `app/src/main/kotlin/com/example/fbsharer/service/`: 无障碍服务核心逻辑。
- `app/src/main/kotlin/com/example/fbsharer/data/`: Room 数据库定义。
