<img src="assets/images/icon.png" align="right" width="64" height="64" alt="憩页图标">

# 憩页 · Peekturn

### 读懂一个词，走近一个更大的世界。

看一段科普视频，追一个新想法，翻开那本一直想读的书。憩页把词义放在你需要的地方，让好奇心走得比英语水平更远一点。

这是一款面向已有一些英语基础、想从真实内容中学到东西的 Android 阅读与视频 App。**看视频时点字幕查词，在句子里理解词义，去其他 App 阅读时也能随手查。**

**[下载 Android APK](https://github.com/Wake-C/peekturn/releases/latest)** · Android 8.0+ · [English](README.en.md)

<picture>
  <source media="(prefers-reduced-motion: reduce)" srcset="assets/video/peekturn-intro-cover-zh.jpg">
  <img src="assets/video/peekturn-intro-zh.gif" width="800" height="800" alt="憩页经典白主题演示：视频与互动字幕、语境解释，以及把手机悬浮查词拖到浏览器单词上，就地查看释义。">
</picture>

[查看静态演示](assets/video/peekturn-intro-cover-zh.jpg)

## 让知识，动起来

导入公开 YouTube 链接，跟着字幕看，遇到生词就点开，再回听这一句，接着往下看。不需要画面时，可以切换到仅音频模式。下载完成的音频、视频及可用字幕支持离线播放。

## 查词，跟着你走

在其他 App 里读到不懂的词？**把憩页的悬浮查词拖到英文单词上。** 松手时截取屏幕，在手机上识别文字，释义直接出现在原来的页面上，不必把句子复制到另一个 App。

离线词典可以直接用，连接自己的 AI 服务后还能看语境解释。此功能需要屏幕共享权限，受保护的页面或禁止悬浮窗的 App 可能无法使用。

## 读懂的，是这一句里的意思

同一个词，在书里和视频里未必是同一个意思。选中单词或短语，查看它在当前句子里的解释、例句和发音。AI 解释需要配置自己的服务，也可能出错。

## 读得更远，懂得更多

导入 EPUB，或通过链接、Android 分享保存网页文章。小说、历史、科学、工作中想弄懂的话题，都可以成为阅读的起点。字体、字号、间距和主题可以调整，阅读位置自动保存。

也可以从当前句子开始朗读，调整倍速，锁屏后继续听。

## 先读起来

1. **安装并打开憩页。** 可以直接读内置的纪伯伦《先知》，也可以导入自己的 EPUB 或文章。
2. **选一段你想读懂的内容。** 遇到生词就选中查词，想听时从当前句子开始朗读。看视频时，在「视频」中粘贴 YouTube 链接，或从其他 App 分享过来。
3. **需要语境解释时，再配置 AI。** 在「设置」中填写自己的模型服务、模型名和 API Key。服务商可能收费，解释也可能出错。

<details>
<summary>在其他 App 里查词</summary>

在设置中开启悬浮查词，或把「悬浮查词」加入 Android 快捷设置。将悬浮图标拖到其他 App 的英文单词上，即可查看解释或离线词典。图标靠边收起后，先点一下展开，再拖动。

需要悬浮窗权限，每次启动还需确认系统屏幕共享。可从快捷设置、图标上的「停止」或通知结束共享。受保护的页面可能无法识别。

</details>

<details>
<summary>导入网页文章</summary>

支持 Substack、WordPress、Ghost 和静态博客的常见排版，包括独立域名。X、Anthropic、OpenAI 等网页使用通用文章提取。如果只能看到付费预览，请先在导入浏览器中登录或打开你有权访问的全文，再导入。

</details>

## 内容与隐私

- 书籍、字幕、阅读与观看进度、学习统计保存在设备上。API Key 在设备上加密保存。
- AI 解释会把选中文字和少量上下文发送给你配置的服务。在线发音与朗读会把要读的文字发送给相应语音服务。
- 跨 App 查词默认关闭，只在松开图标时截取屏幕，并在本机识别文字。截图和识别出的文字不会保存到解释缓存。
- 视频和字幕能否获取取决于来源。临时缓冲不等于离线下载，请仅下载你有权保存的内容。字幕分组不会纠正原有的识别错误。

演示视频为 [Kurzgesagt - In a Nutshell](https://www.youtube.com/@kurzgesagt) 的 *The Drug To Master Reality*，视频画面归原作者所有。浏览器截图使用[原创示例文章](assets/demo/curiosity.html)，不含私人阅读内容。

---

[第三方声明](THIRD_PARTY_NOTICES.md) · [源码许可](LICENSE) · [开发与交付](agent-doc/essential/DELIVERY.md)
