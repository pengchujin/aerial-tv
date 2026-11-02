# 当贝 X7 Max（v6.10.0）

2026-11-01 实测，Android 14 / SDK 34，`horse_popular`，MT9952。基于 Aerial 1.77。
关联亮度问题：[#598](https://github.com/pengchujin/aerial-tv/issues/598)。

## 普通亮度与 10+ Boost

这台固件在 boot class path 中提供 `com.xgimi.gmpf.api.DisplayManager`。
普通应用可以反射调用，不需要 root、系统签名或加载原生设置 APK：

| 接口 | 实测含义 |
| --- | --- |
| `getDlpLumensLevel()` / `setDlpLumensLevel(byte)` | 普通光源档位 1–10 |
| `getLensDispMode()` / `setLensDispMode(int)` | 0 为普通模式，1 为原生 10+ Boost |
| `getDispLumensLevel()` | 在本轮变化中始终返回 1000，不能用于判断 Boost |
| `getDlpLumensMode()` | 在本轮变化中始终返回 0，不能用于判断 Boost |

普通 10 和 10+ 的 DLP getter 都返回 10。Boost 开着时，普通 DLP setter 的调用
能返回，但档位保持 10；先退出 Boost，DLP setter 才生效。这是 #598 中滑块无效的原因。
2026-11-02 补测从普通 7 档直接开启 Boost：Lens 模式变为 1，DLP getter 仍为 7。
因此 DLP getter 在 Boost 中不能表示当前亮度；显示 10+ 时滑条应填满，退出后才使用普通档位。
原生亮度从 10+ 改到 8 前后，Android 的 global / system / secure 三张设置表没有变化；
`screen_brightness=153`，`screen_brightness_mode=0`。

能力检测读取固件的 `support_brightness_boost`，此机返回 `2`。配置服务需要通过
`com.xgimi.xgimiservice.services.XgimiServices`（包名 `com.xgimi.xgimiservice`）绑定；
从 `IXgimiService.getGimiConfig().getAllFeatureSupport()` 读取后解除绑定。
直接调用尚未绑定的 `GimiConfigManager` 会抛空指针异常。
只有 feature 值为 `2` 且模式 getter 返回 0/1 时处理 Boost；不按型号或厂商名猜测支持，
其他版本的 Boost 契约尚未验证。

## 控制中心行为

- 普通亮度继续显示百分比，10+ 显示 `10+`，并有单独的 Boost 开关。
- 从 10+ 按一次左键退出 Boost，回到普通 10；继续向左为 9、8 等。
- 普通亮度加到 10 不自动进入 Boost；10+ 状态按右键保持不变。
- 开启 Boost 可通过投影仪原生设置或 Aerial 独立开关，不能靠一直按右键进入。
- 两个入口控制的是同一个光源状态。原生设置开启 10+，控制中心轮询后同步显示开启；
  Aerial 下调亮度后也会退出原生 Boost，没有两套独立的物理亮度。
- 开启 Boost 前检查自动亮度、3D 和 IMAX；任一开启或接口读取失败时拒绝开启并提示。
  此机 `getCurrent3DFormat()` 与 `EN_XGIMI_3D_NONE` 均为 1；不能把 0 当成无 3D。
  原生面板会关闭环境自适应，Aerial 则保留用户设置，请用户自行关闭。

## 验证范围

为保留户主正式版签名、照片和设置，使用独立包名的 guard release 测试构建，安装后
执行 `cmd package compile -m speed -f <测试包名>`。应用代码与标准版共用。
2026-11-01 的原组合补丁测试中，用遥控器操作控制中心，并由独立进程读取同一 HAL
getter。与亮度下调相关的结果如下；它们不是拆分后构建的复测结果：

| 操作 | DLP 档位 | Lens 模式 | 界面 |
| --- | --- | --- | --- |
| Boost 下按右键 | 10 | 1 | 保持 10+ |
| Boost 下按一次左键 | 10 | 0 | 100 |
| 再连续三次左键 | 7 | 0 | 70 |
| 连续右键至上限并再按一次 | 10 | 0 | 100 |
| 当贝原生面板开启 Boost | 10 | 1 | 原生 10+，Aerial 同步 10+ |

测试后恢复原始 10+，卸载独立测试包，正式版与其设置保留。
未用照度计测量流明，未测试其他机型或重启后的持久性。

## 2026-11-02：拆分后的纯修复验收

独立包名的 guard release（安装后 compile speed）已复测：控制中心无 Boost 开关；
10+ 下右键保持不变，左键回普通 10，再连续三次左键到 7，右键至上限并再按一次
仍为普通 10。控制中心打开期间，从独立进程调用同一固件接口开启 Boost，轮询显示
10+，再次左键退出到普通 10。每步均由独立进程分别读取旧版和 V3 HAL，模式及档位一致。
这次外部切换通过固件接口准备状态，没有操作原生设置面板。

standard Debug、Release 完整单元测试各 169 项通过（含 6 项 Boost 回归）；
standard release 和独立 guard release 构建、lintVital 通过。
测试前后均为普通 10、自动亮度关闭；正式版及其应用数据保留。

## 2026-11-02：独立开关增量验收

在纯修复之上增加开关。standard Debug、Release 完整单元测试各 170 项通过，
standard release 和独立 guard release 构建、lintVital 通过。
最终 guard release 已 compile speed，并实测普通 7 → 开启 Boost（Lens 1、DLP 仍为 7，
界面 10+ 且滑条填满）→ 关闭（普通 10）→ 再开启。滑块下调到普通 10、再到 7、
上调至普通 10 并再按右键均正常，两套 HAL 读数一致。
开启限制的拒绝分支只有单元测试，未在真机开启自动亮度、3D 或 IMAX。
测试包已移除，普通 10 / 自动亮度关闭及正式版保留；未进行重启测试。
