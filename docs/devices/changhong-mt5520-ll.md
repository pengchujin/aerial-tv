# ChangHong mt5520_ll (Android 5.1)

2026-11-05：按 PR #1265 审阅意见改为各项能力独立检测，不再按型号与产品启用。变基到上游 1.85 后用同签名 standard release 覆盖安装，提交者（机主）在真机上确认设置、网络、图像、信号源和背光均正常。

2026-11-04（旧版）：standard release 保留数据覆盖安装后，提交者（机主）在真机上确认修改验证完成并提交 PR。真机结论来自提交者反馈，仅限以下型号与固件，不能代替本轮改动的验收，也不代表其他长虹电视已验证。用户已接受网络入口停在原厂「网络与蓝牙」，不再要求直接展开 Wi-Fi。

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

`ChanghongSettings.kt` 通过 PackageManager 检测 EasySetting 服务，只有服务存在、启用、导出且 Aerial 具有调用权限才选它。只有安装包、没有可用服务时不改设置入口。控制中心、快捷设置以及安卓设置磁贴使用同一路由；实际启动失败或选好入口后服务被停用，仍回退到原有 Activity，家长锁检查仍在启动之前。

信号源独立检测 `com.changhong.SourceMenu/com.changhong.source.SourceMenuService` 是否存在、启用、导出且有调用权限，不依赖 EasySetting。action 为 `CH_MENU_SOURCE`，extra 为 `MenuSource=menuSource`、`keyCode=2001`；服务缺失或启动失败继续尝试原有信号源入口，不把「服务存在」当作「打开成功」。

设置服务、信号源服务与背光类之间没有总开关，也不相互推断。`devices.json`、`DeviceProfile`、配置诊断导出及原有配置测试恢复到 PR 前的版本；不增加产品匹配，不改本机 profile 的替换规则，不改写已保存的配置。

## 背光

`/system/framework/com.changhong.tvos.jar` 在 boot classpath 中。原厂侧栏使用：

1. `com.changhong.tvos.common.TVManager.getInstance(Context)`。
2. `getPictureManager()`。
3. `getBackLight()` / `setBackLight(int)`，范围 0–100，setter 返回 boolean。

这是面板背光，和同一 SDK 的 `getBrightness()` / `setBrightness()`（图像亮度）不同，也不是 `Settings.System.screen_brightness`。`ChanghongPicture` 独立检测 `TVManager` 类并反射调用该接口，读取接受 0–100，写入限制在 1–100，不依赖型号、产品、EasySetting、SourceMenu、root、ADB、外部 APK 或额外权限。只有读到有效背光值时才走这条路：类不存在，或类存在但取不到实例 / PictureManager、调用异常、读数超出 0–100，都保留原有亮度路径。写入被拒、抛异常或读回不一致则在本次进程中停用滑块，不再写安卓亮度。图像入口仍按 EasySetting 自身的检测结果选择，缺失或失败保留原有回退。

验证边界：从这台固件的 `ITVService` / 枚举定义核对 Binder 参数后，ADB root 调用背光完成了小幅降低、读回和恢复原值；安卓亮度值保持不变。**这只能证明硬件服务读写通路，不证明普通应用权限或新 Aerial 滑块有效。** 独立 `app_process` 探针被此固件终止，不能用它代替应用实测。

## 验证与安装

- 本轮真机：变基到上游 1.85 后，同签名 standard release 用 `install -r` 保留数据覆盖安装，回拉 APK 与构建哈希一致，偏好文件、无障碍服务列表和 app-ops 不变。提交者于 2026-11-05 在真机上确认控制中心设置按钮与安卓设置磁贴、网络、图像、信号源菜单、亮度滑块调背光均正常。
- 旧版真机：standard release 保留数据覆盖安装，回拉 APK 与测试构建哈希一致。提交者于 2026-11-04 在真机上确认验证完成；没有将安装成功或 ADB root 探针当作功能验收结论。
- 本轮自动化（变基到 1.85 后）：standard / guard 的 debug 与 release 全量单元测试各 247 项通过（各含 32 项长虹专项），两个 release 变体的 lintVital 通过。覆盖缺失能力不抢占原入口、单项能力存在即可使用、不依赖型号/产品/本机 profile、服务未导出/停用/权限拒绝、服务启动失败或失效后的回退、设置磁贴的实际启动路径，TVOS 类缺失或读不到有效值时保留安卓亮度读写（没有 TVOS 类的电视不受写入失败标记影响）、面板背光为 0 时可读取并上调，以及 TVOS 写入失败后的滑块停用。`devices.json`、`DeviceProfile.kt`、`Diagnostics.kt` 和 `DeviceProfileTest.kt` 与 PR 前完全一致。
- 长虹测试使用 API 28（Robolectric 4.17 不提供 API 22），不替代 Android 5.1 真机验证。
- 旧版全量 lint 有 26 个原有错误（遥控按键 RestrictedApi、影片 UnstableApi、资源翻译及清单等），没有为本适配改动无关代码或压制检查。旧版 release 的 lintVital 通过；该结果不替代本轮构建检查。

- 覆盖安装必须使用同签名 release APK。不要为了绕过签名不一致而卸载 Aerial、清空数据或修改系统签名校验；地址、签名和备份只保留在本机。
- 本机是 API 22，不支持 Android 9 起的 dex metadata 安装流程；不能套用 `install-multiple` 搭配 `.dm`。
- 用户已接受原厂网络分类页，无需继续查 Wi-Fi 列表直达。
