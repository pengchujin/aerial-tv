# ChangHong mt5520_ll (Android 5.1)

2026-11-04：standard release 保留数据覆盖安装后，户主确认修改验证完成并要求提交 PR。真机结论来自户主反馈，仅限以下型号与固件；不代表其他长虹电视已验证。用户已接受网络入口停在原厂「网络与蓝牙」，不再要求直接展开 Wi-Fi。

## 设备与边界

- model（`ro.product.model`）：`长虹智能电视`，manufacturer：`ChangHong`，product（`ro.product.name` / `Build.PRODUCT`）：`mt5520_ll`，device：`ChangHong`，API 22。2026-11-03 通过 ADB 只读核实；`mt5520_ll` 不是 `Build.MODEL`。
- 使用完整版 `app/`，不是 Android 4.x 的 Lite。
- 固件仍带 `com.android.settings`，标准设置 Intent 和该应用磁贴会打开安卓原生界面。
- 电视地址、原始数值、签名及备份文件只记在 `local/TVS.md`。

## 原厂侧栏

真正的左侧悬浮设置是 `com.changhong.easysetting/.MainService`，服务 action 为 `Changhong.EasySetting`。它不是 Activity，没有可供桌面打开的 MAIN 页面。该服务导出且清单没有调用权限要求；在 Aerial 前台以 ADB 启动，截图确认显示左侧「全部设置」。

- 不带页面参数：完整侧栏。
- `StatusBar_to_netset=true`：进入「网络与蓝牙」。**焦点是有线网络，无线网络在下一项；不是 Wi-Fi 列表直达。**
- `StatusBar_to_videoset=true`：图像页入口。

`MainService.onStart` 的网络分支调用 `NetworkSetting.refreshForFocus(5)`，后者在原厂代码中写死 `getEthItem().requestfouse()`。目前没有找到直接选中或展开无线网络的公开参数。不要编造 Wi-Fi extra，也不要靠延时注入遥控按键冒充直达。

真机控件树中，无线网络标签是 `id/vch_item_left_text`，所在行 `id/vch_item_first_class` 可聚焦但不可点击。Aerial 现有守护明确不读取窗口内容（`canRetrieveWindowContent=false`）；若以后考虑无障碍定位，必须先向用户说明新增权限并取得同意，不能静默改变守护的隐私边界，也不能只凭控件树就声称展开 Wi-Fi 已可用。

按户主 2026-11-03 的明确要求，这组适配只按机型配置启用，不再通过包或服务是否存在决定。`assets/devices.json` 同时匹配 `models: ["长虹智能电视"]` 和 `products: ["mt5520_ll"]`，启用 `changhongTvos`，防止其他同名长虹机型误用。`ChanghongSettings.kt`、信号源菜单和背光均读取这一配置；其他机型即使装有相同包或服务也不进入长虹适配。控制中心、快捷设置以及安卓设置磁贴使用同一路由；实际启动服务失败仍回退到原有 Activity，家长锁检查仍在启动之前。

信号源使用 `com.changhong.SourceMenu/com.changhong.source.SourceMenuService`，action 为 `CH_MENU_SOURCE`，extra 为 `MenuSource=menuSource`、`keyCode=2001`；启动失败继续尝试原有信号源入口，不把「命中机型」当作「打开成功」。

2026-11-04 补充隔离：即使其他电视的本机 `device_profile` 带有 `changhongTvos: true`，加载时也必须通过内置型号和产品白名单，不允许绕过。只过滤运行时的长虹标记，不改保存的数据或其他修正。直接传入 EasySetting Intent 也受同一条件保护；非目标电视保留原 Activity 处理，不进入长虹服务或长虹专用回退。

## 背光

`/system/framework/com.changhong.tvos.jar` 在 boot classpath 中。原厂侧栏使用：

1. `com.changhong.tvos.common.TVManager.getInstance(Context)`。
2. `getPictureManager()`。
3. `getBackLight()` / `setBackLight(int)`，范围 0–100，setter 返回 boolean。

这是面板背光，和同一 SDK 的 `getBrightness()` / `setBrightness()`（图像亮度）不同，也不是 `Settings.System.screen_brightness`。`ChanghongPicture` 使用反射调用该接口，限制在 1–100，不依赖 root、ADB、外部 APK 或额外权限。若无法读取、写入失败或读回不一致，隐藏失效滑块并尝试原厂图像入口，不再写安卓亮度。

验证边界：从这台固件的 `ITVService` / 枚举定义核对 Binder 参数后，ADB root 调用背光完成了小幅降低、读回和恢复原值；安卓亮度值保持不变。**这只能证明硬件服务读写通路，不证明普通应用权限或新 Aerial 滑块有效。** 独立 `app_process` 探针被此固件终止，不能用它代替应用实测。

## 验证与安装

- 真机：standard release 已保留数据覆盖安装，回拉 APK 与测试构建哈希一致。户主于 2026-11-04 确认验证完成；没有将安装成功或 ADB root 探针当作功能验收结论。
- 自动化：回归先复现配置与 Intent 绕过限制的 7 个失败用例，修复后 standard / guard 各 80 个系统测试通过，包括非目标电视原设置磁贴的实际启动路径、安卓亮度读写、信号源回退和配置序列化。两个 release 构建（含 lintVital）与 Lite 共享代码编译通过；原有 11 条机型配置逐条对比未变。
- 长虹测试使用 API 28（Robolectric 4.17 不提供 API 22），不替代 Android 5.1 真机验证。
- 全量 lint 仍有 26 个原有错误（遥控按键 RestrictedApi、影片 UnstableApi、资源翻译及清单等），没有为本适配改动无关代码或压制检查。release 自带的 lintVital 通过；全量 lint 报告没有新增长虹代码或 NewApi 错误。

- 覆盖安装必须使用同签名 release APK。不要为了绕过签名不一致而卸载 Aerial、清空数据或修改系统签名校验；地址、签名和备份只保留在本机。
- 本机是 API 22，不支持 Android 9 起的 dex metadata 安装流程；不能套用 `install-multiple` 搭配 `.dm`。
- 用户已接受原厂网络分类页，无需继续查 Wi-Fi 列表直达。
