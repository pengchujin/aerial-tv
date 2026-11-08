# GitHub 打包和发布

工作流：`.github/workflows/build-release.yml`。

## 首次配置

在自己的仓库进入 Settings → Secrets and variables → Actions，添加四个 Repository secrets：

| 名称 | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | keystore 文件的 Base64 编码 |
| `ANDROID_STORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | 密钥密码 |

Windows PowerShell 可以将已有签名文件编码后复制到剪贴板：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path 'local/signing.keystore').Path)) | Set-Clipboard
```

其余三个值对应 `local/signing.properties` 的 `storePassword`、`keyAlias`、`keyPassword`。不要把签名文件、密码或 Base64 内容提交进仓库。Base64 不是加密。

必须一直使用同一把签名密钥，才能覆盖升级。自己生成的密钥无法覆盖安装原作者签名的 APK。缺少任何签名 Secret 时工作流直接失败，不会退回 debug 签名。

## 手动打包

将工作流提交到默认分支后，进入 Actions → Build APKs and draft release → Run workflow，选择包含此工作流的分支并运行。

成功后在本次运行的 Artifacts 下载 APK 压缩包，保留 30 天。手动运行只上传构建产物，不创建 Release。

## 推送 tag 打包并创建发布草稿

先更新并提交版本号：完整版修改 `app/build.gradle.kts` 中的 `versionName` 和 `versionCode`；Lite 有修改时更新它自己的版本号。tag 名称不会自动修改 APK 版本号。发 TCL 版本时，versionCode 必须递增，并高于已经安装的测试版本。

在要发布的提交上创建一个尚未使用的 `v` 开头的 tag，然后推送。例如：

```bash
git tag v1.87
git push origin v1.87
```

无需指定分支：工作流编译 tag 指向的提交，该提交必须包含工作流文件。`v1.87-beta.1` 等名称也会触发。

流程使用 JDK 17，运行完整版两个 flavor 和 Lite 的单元测试、Lite release lint，再编译三个 release APK。成功后创建 Release 草稿，附带：

- `aerial-standard.apk`
- `aerial-guard.apk`
- `aerial-lite.apk`

在真机验证后编辑草稿的发布说明，再点击 Publish release；测试版本发布时勾选 Set as a pre-release。CI 通过不等于已经通过真机验证。电视安装和 `.dm` 的使用见 `HANDOFF.md`「日常操作」。

重新运行同一 tag 时可以更新现有草稿的 APK；如果 Release 已正式发布，工作流会拒绝覆盖其附件，应使用新版本 tag。

此流程只发布到运行工作流的仓库，不部署原项目官网，也不修改应用或安装脚本中的更新地址。
