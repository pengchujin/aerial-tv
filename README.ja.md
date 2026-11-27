<div align="center">

<img src="docs/brand/icon.png" width="96" alt="Aerial アイコン">

# Aerial

**Android TV のための、静かなホーム画面。**

Apple TV をお手本にした、広告もおすすめフィードもないホーム画面。あるのはあなたのアプリと壁紙、そして空撮スクリーンセーバーだけです。<br>
Xiaomi、TCL、Skyworth、ソニーなど中国で販売されているテレビ向けに作られており、セットトップボックスやプロジェクターでも動きます。起動時に自動で立ち上がり、スタンバイ後も消えません。

[中文](README.md) · [English](README.en.md) · **日本語**

<a href="https://aerial.qust.me/en/"><img alt="公式サイト" src="https://img.shields.io/badge/%E5%85%AC%E5%BC%8F%E3%82%B5%E3%82%A4%E3%83%88-1f6feb?style=for-the-badge&logo=safari&logoColor=white"></a>
<a href="https://github.com/pengchujin/aerial-tv/releases/latest"><img alt="APK ダウンロード" src="https://img.shields.io/badge/APK%20%E3%83%80%E3%82%A6%E3%83%B3%E3%83%AD%E3%83%BC%E3%83%89-34a853?style=for-the-badge&logo=android&logoColor=white"></a>
<a href="#install"><img alt="インストール" src="https://img.shields.io/badge/%E3%82%A4%E3%83%B3%E3%82%B9%E3%83%88%E3%83%BC%E3%83%AB-d97757?style=for-the-badge&logo=gnubash&logoColor=white"></a>

<a href="https://github.com/pengchujin/aerial-tv/releases/latest"><img alt="バージョン" src="https://img.shields.io/github/v/release/pengchujin/aerial-tv?style=flat-square&label=%E3%83%90%E3%83%BC%E3%82%B8%E3%83%A7%E3%83%B3&color=2997ff"></a>
<a href="LICENSE"><img alt="ライセンス: MIT" src="https://img.shields.io/badge/%E3%83%A9%E3%82%A4%E3%82%BB%E3%83%B3%E3%82%B9-MIT-555555?style=flat-square"></a>
<img alt="Android TV 5.0+" src="https://img.shields.io/badge/Android%20TV-5.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white">
<a href="#lite"><img alt="Lite 4.0–4.4 ベータ" src="https://img.shields.io/badge/Lite-4.0%E2%80%934.4%20beta-f5a623?style=flat-square&logo=android&logoColor=white"></a>

![Aerial のホーム画面](site/img/home.jpg)

| テレビ標準のホーム画面 | Aerial を入れると |
|:---:|:---:|
| <img src="site/img/stock-xiaomi.jpg" alt="番組のおすすめや会員バッジで埋め尽くされた標準のホーム画面"> | <img src="site/img/home.jpg" alt="Aerial のホーム画面"> |

</div>

## 機能

<p align="center">
  <img src="site/img/focus.jpg" width="49%" alt="最上段の下にあるガラスの棚">
  <img src="site/img/grid.jpg" width="49%" alt="フォルダを含むアプリ一覧">
</p>

- **ホーム**：お気に入りのアプリは下部のガラスの棚に 1 列で並び、その下にすべてのアプリが並びます。Xiaomi のアプリストアや Mi Home のような隠れたシステムアプリも表示されます。
- **整理**：OK を長押し（またはメニューボタン）で、アプリの移動、フォルダへの格納、非表示、アンインストール、電源オンやスリープ解除時に自動で開く設定ができます。ホームに固定した HDMI 入力には、接続している機器（Apple TV、PlayStation、Switch、Xbox、パソコンなど）を指定してそのアイコンを使ったり、スマホで入力した名前を付けたりできます。
- **スマホから並べ替え**：テレビに表示されたコードをスキャンすると、スマホにテレビと同じ並びのホームが表示されます。タイルをドラッグして並べ替え、タップして名前やアイコンの変更、フォルダへの格納、非表示ができ、HDMI などの入力もホームに追加できます。
- **コントロールセンター**：最上段で上を押すと右上に開きます。入力切替、ネットワーク、設定、音量、明るさ、パーソナライズがあります。
- **スクリーンセーバー**：Apple TV の空撮映像 114 本（テレビが対応していれば 4K または 4K Dolby Vision）、写真、時計、スマホから送ったローカル動画（[ガイド、中国語](docs/SCREENSAVER_VIDEOS.md)）。
- **壁紙**：Bing の今日の一枚、内蔵の 14 枚、スマホでコードをスキャンしてアップロードした写真や動画、あるいは空撮映像。空撮映像はホームで 10 秒間操作がないとアプリの背後で再生され、いずれかのキーを押すとその時点のフレームで止まります。
- **パーソナライズ**：1 行あたりのアプリ数（4・5・6）、行数（1・2）、時計の大きさと位置、アプリ名の表示、フォーカスのスタイル、操作音。

<p align="center">
  <img src="site/img/folder.jpg" width="49%" alt="開いたフォルダ">
  <img src="site/img/control-center.jpg" width="49%" alt="コントロールセンター">
</p>

<p align="center">
  <img src="site/img/screensaver-aerial.jpg" width="49%" alt="空撮スクリーンセーバー">
  <img src="site/img/columns.jpg" width="49%" alt="1 行 6 アプリにしたパーソナライズ画面">
</p>

<a id="install"></a>

## インストール

root 不要、システムアプリには手を付けず、元に戻すこともできます。パソコンとテレビは同じネットワークにつないでください。

### 1. ADB デバッグをオンにする

中国向けファームウェアのメニューは中国語です。括弧内はその表記です。

| テレビ | 手順 |
|---|---|
| **Xiaomi / Redmi** | 設定（设置）→ デバイス情報（关于）→ モデル（产品型号）を開発者モードになるまで押し続け、設定 → アカウントとセキュリティ（账号与安全）で ADB デバッグ（ADB 调试）と提供元不明のアプリのインストール（安装未知来源的应用）をオン |
| **TCL / FFALCON** | 設定（设置）→ デバイス情報（关于）→ 本機情報（本机信息）で上下左右をすばやく押し、その他（更多）で ADB をオン |
| **Haier / MOOKA** | テレビ本体の入力切替ボタンを押してから、リモコンで左左上右と押す。工場メニューで ADB 调试开关 をオンにし、それ以外は変更しない |
| **OPPO（S1 を含む）** | 設定（设置）→ システム（系统）→ 本機について（关于本机）→ モデル（设备型号）で上上右右と押してから OK。次に設定 → システム → 開発者向けオプション（开发者选项）で USB デバッグ（USB 调试）をオン。IP アドレスは設定 → ネットワーク → 接続中のネットワークの一番下にあります |
| **その他の Android TV** | 設定 → デバイス設定 → デバイス情報 → ビルドを 7 回押し、開発者向けオプションで USB デバッグをオン |

テレビの IP アドレスは設定 → ネットワーク → 接続中のネットワークで確認できます。初めて接続するとき、USB デバッグのスイッチがあるテレビではデバッグを許可するか聞かれるので、「常に許可」を選んでください。ワイヤレスデバッグしかないテレビではこの確認は出ません。次の段落を参照してください。

**Android 11 以降でワイヤレスデバッグしかない（USB／ネットワークデバッグのスイッチがない）テレビは、接続の前にペアリングが必要です**（Android 14 の Sony BRAVIA など。#747）。こうしたテレビには通常のポートがないため、`adb connect <IP>:5555` だけでは接続できません。テレビで設定 → システム → 開発者向けオプション → **ワイヤレスデバッグ** → **ペア設定コードによるデバイスのペア設定** を開き、パソコンでペアリング画面に表示された IP:ポートと 6 桁のコードを入力します。

```bash
adb pair 192.168.1.20:37115   # テレビのペアリング画面に表示されたポート（例。実際はテレビの表示に従う）
# ペア設定コードを聞かれたら、テレビに表示された 6 桁を入力
adb connect 192.168.1.20:39201   # ワイヤレスデバッグのメイン画面にある別の IP:ポート（例）
```

あとは通常どおりインストールします。今後は普通に `<テレビの IP>:5555` を使いたい場合は、接続後に一度 `adb tcpip 5555` を実行してください。一度ペアリングしたパソコンは再度ペアリングする必要はありません。`install.sh` と [インストールガイド](site/en/agent.md) のエージェントもこの手順を案内します。一部の TCL ファームウェア（Q10M、Q10L、Crane 6 Ultra など）にはペア設定コードの項目がそもそもありません。その場合は、下のブランド別ガイドのとおり、USB ケーブルで一度だけ認証してください。

ADB を開けないテレビも含めた、ブランド別のより詳しいガイドは [docs/devices/adb-by-brand.md](docs/devices/adb-by-brand.md)（中国語）にあります。

### 2. インストールする

**エージェントを使う（おすすめ）**：パソコン上でコマンドを実行できるエージェント（Claude Code、Codex、WorkBuddy、Trae、Cursor、Doubao デスクトップ版など）に次の文を貼り付けます。

```text
Android TV 向けのオープンソースのホーム画面 Aerial を私のテレビにインストールし、テレビが起動時に開くホーム画面にしてください。
テレビの IP アドレス：<テレビの IP>
ADB デバッグをオンのままにする：はい

インストールガイドはこちらです：https://aerial.qust.me/en/agent.md
（開けない場合は https://raw.githubusercontent.com/pengchujin/aerial-tv/main/site/en/agent.md を使ってください）

始める前にガイドを最後まで読んでください。そのうえで、手順を説明するだけでなく、私のパソコンで自分でコマンドを実行してください。テレビ側で私が操作する必要があるときは教えてください。
最後に、「うまくいきました」だけでなく、「Check」セクションにある次のコマンドの生の出力を貼り付けてください：
- adb shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
- adb shell settings get secure enabled_accessibility_services
- ホームボタンを押したあとの adb shell "dumpsys window | grep mCurrentFocus"
```

スマホアプリやウェブ版のチャット（スマホ版 Doubao を含む）は手順を教えてくれるだけで、何も実行できません。実行できるデスクトップ版（Doubao デスクトップ版や Doubao Work、WorkBuddy、Qwen デスクトップ版、Trae、Claude Code、Codex など）を使ってください。エージェントが完了したと言ったら、貼り付けられた `mCurrentFocus` の出力に `app.orchard.tv` が含まれていることを確認してください。

**USB メモリ（パソコンなし）**：APK を USB メモリにコピーし、テレビのファイルマネージャーで開きます。

| ダウンロード | 対象 |
|---|---|
| [aerial-standard.apk](https://aerial.qust.me/download/aerial-standard.apk) | Android 5.0 以降。まずはこちらを試してください |
| [aerial-guard.apk](https://aerial.qust.me/download/aerial-guard.apk) | テレビがホーム画面アプリのインストールを拒否し、standard が入らない場合 |
| [aerial-lite.apk](https://aerial.qust.me/download/aerial-lite.apk) | Android 4.0〜4.4（下の [Aerial Lite](#lite) を参照） |

インストールしたらホームボタンを押します。どのアプリを使うか聞かれたら Aerial を選び、「常時」を選べば完了です（素の Android TV はたいていこれで済みます）。聞かれずに元のランチャーに戻ってしまう場合（中国ブランドのテレビの多く）は、アプリ一覧から Aerial を開いてください。「Aerial をホーム画面にする」画面が表示され、その項目からテレビのホームガード（ユーザー補助）と通知へのアクセスの設定を開けます。テレビの設定にこれらがない場合、Aerial は普通のアプリとしてしか動きません。ホームを乗っ取るには、下のコマンドを実行するか、エージェントに一度任せてください（上書きインストールされ、設定はそのまま残ります）。

**パソコンなしで**：スマホアプリの Beetle ADB（甲壳虫 ADB 助手）や Super Xiaoai（超级小爱）でインストールしたユーザーもいますが、それぞれについて私たちは検証していません。手順は上の Windows の手順と同じです。インストール後、可能ならパソコンからチェックを実行してください（[Check](site/en/agent.md#check-required) を参照）。

**コマンド 1 つで（macOS / Linux）**：

```bash
curl -fsSL https://aerial.qust.me/install.sh | KEEP_ADB=1 TV=192.168.1.20 bash
```

ワイヤレスデバッグしかないテレビ（手順 1）では、このコマンドの前に `export ADB_PAIR=<ペアリング画面の IP:ポート> ADB_CODE=<6 桁のコード> ADB_CONNECT=<メイン画面の IP:ポート>` を実行します。ペア設定コードの項目がないテレビ（TCL Q10M、Q10L、Crane 6 Ultra など）は、まず [ブランド別ガイド](docs/devices/adb-by-brand.md) に従って USB ケーブルで一度認証し、`adb tcpip 5555` を実行してから、通常どおりコマンドを実行してください。

**手動で**：[aerial-standard.apk](https://aerial.qust.me/download/aerial-standard.apk) をダウンロードします。ワイヤレスデバッグしかないテレビ（手順 1）では、先に次のどちらかを済ませてからコマンドを実行してください。

- **ペア設定コードの項目がある場合**（Android 14 の Sony BRAVIA など）：手順 1 のとおりペアリングし、接続後に一度 `adb tcpip 5555` を実行すれば、コマンドの 1 行目は 5555 のままで使えます。実行しない場合は、1 行目にワイヤレスデバッグのメイン画面にある IP:ポートを使ってください。残りは同じです。
- **ペア設定コードの項目がない場合**（TCL Q10M、Q10L、Crane 6 Ultra など）：[ブランド別ガイド](docs/devices/adb-by-brand.md) に従って USB ケーブルでテレビとパソコンをつなぎ、認証されたら `adb tcpip 5555` を実行します。それまでは 1 行目の `adb connect <テレビの IP>:5555` は使えません。

```bash
adb connect 192.168.1.20:5555
adb install -r aerial-standard.apk
adb shell pm grant app.orchard.tv android.permission.WRITE_SECURE_SETTINGS
adb shell cmd notification allow_listener app.orchard.tv/app.orchard.tv.guard.RevivalListener
adb shell appops set app.orchard.tv SYSTEM_ALERT_WINDOW allow
adb shell am start -n app.orchard.tv/.MainActivity
```

Windows ではエージェントを使うか、[インストールガイド](site/en/agent.md) の Windows の手順に従ってください。

**アップデート**：1.32 以降は、新しいバージョンがあるとコントロールセンターに「新しいバージョン」が表示されます。そこで「ダウンロードしてインストール」を選び、テレビ自身のインストーラーで確定してください（Xiaomi と TCL で確認済み）。アップデート後も Aerial はすべての設定を保ったまま戻ってきます。アプリによるアプリのインストールを許可しないテレビでは、アップデートはインストールコマンドの再実行になります。アップデート画面の QR コードをスマホで読み取ると、そのテレビ用のコマンドが開くので、コピーしたりエージェントに渡したりできます。1.31 以前にはこのボタンがないため、今回だけはインストールコマンドを再実行してください。

<a id="lite"></a>

### Android 4.0〜4.4：Aerial Lite（ベータ）

Android 4.0〜4.4 のテレビやボックスでは、フル版は動きません（UI フレームワークが Android 5.0 を必要とするため）。そのため、別アプリの **Aerial Lite** があります。パッケージは `app.orchard.tv.lite` で、見た目はフル版と同じです。

> **Lite は最初のリリース（1.0）で、ベータ版です。** Android 4.0.4 / 4.4.4 のエミュレーターと新しめの Xiaomi・TCL のテレビでテストしましたが、実機の 4.x テレビではまだ試しておらず、細かな問題がまだ多く残っているかもしれません。**Android 4.0〜4.4 専用です**。Android 5.0 以降では上のフル版を使ってください。問題はタイトルに Lite と入れて [デバイスサポートフォーム](https://github.com/pengchujin/aerial-tv/issues/new?template=device.yml) から報告してください。

**コマンド 1 つで（macOS / Linux）**：フル版と同じコマンドで、Android 4.x のテレビならスクリプトが自動で Lite をインストールします。明示的に指定する場合：

```bash
curl -fsSL https://aerial.qust.me/install.sh | TV=192.168.1.20 bash -s lite
```

**手動で**：[aerial-lite.apk](https://aerial.qust.me/download/aerial-lite.apk) をダウンロードしてから：

```bash
adb connect 192.168.1.20:5555
adb install -r aerial-lite.apk
adb shell pm grant app.orchard.tv.lite android.permission.WRITE_SECURE_SETTINGS   # 明るさ調整用。Android 4.0 では拒否されるが問題ない
adb shell am start -n app.orchard.tv.lite/.HomeActivity
```

**USB メモリ**：`aerial-lite.apk` を USB メモリにコピーし、テレビのファイルマネージャーで開きます。

**インストール後**：リモコンの **ホーム** を押し、どのアプリを使うか聞かれたら **Aerial Lite** を選んで **常時** を選びます。何も聞かれずに元のランチャーが開く場合は、ファームウェアがホームを自社ランチャー用に確保しているので、アプリ一覧から Lite を開いてください。

**フル版との違い**：ホームガード（ユーザー補助による乗っ取り）がないため、ホームと起動時の動作は Android のデフォルトホーム設定に依存します。空撮壁紙、マイビデオ壁紙、入力タイル（4.x にはテレビ入力フレームワークがないため、入力ボタンはテレビ自身の入力パネルを開きます）、アプリ内アップデート（再インストールでアップデートし、設定は残ります）もありません。空撮スクリーンセーバー、写真と今日の一枚、時計、フォルダ、長押しメニュー、コントロールセンター、パーソナライズ、スマホ用ページはすべて使えます。詳しくは [docs/LITE.md](docs/LITE.md)（中国語）を参照してください。

**元に戻す**：テレビで設定 → アプリ → Aerial Lite → デフォルトをクリアしてからホームを押し、元のランチャーを選びます。またはアンインストールします：`adb uninstall app.orchard.tv.lite`（コマンド 1 つの方法の `bash -s restore --uninstall` でも削除されます）。

### 元に戻す

```bash
curl -fsSL https://aerial.qust.me/install.sh | TV=192.168.1.20 bash -s restore
```

`--uninstall` を付ける（`bash -s restore --uninstall`）と Aerial も削除します。bash がない環境（Windows）向けの adb コマンドは、[インストールガイドの Undo](site/en/agent.md#undo) に 1 つずつ載っています。

AI に任せる場合（パソコンでコマンドを実行できる必要があります）は、次の文を貼り付けてください：

```text
https://aerial.qust.me/en/agent.md の「Undo」セクションに従って、私のテレビ（IP アドレス：<テレビの IP>）を元のホーム画面に戻してください。Aerial はアンインストールしないでください。
私のパソコンで自分でコマンドを実行し、最後に settings get secure enabled_accessibility_services と、ホームボタンを押したあとの dumpsys window | grep mCurrentFocus の生の出力を貼り付けてください。
```

### トラブルシューティング

- **自分のテレビで動く？** 下の [対応テレビ](#supported-tvs) を参照してください。

- **USB メモリから APK だけをインストールした**：1.32 以降、Aerial はテレビのアプリ一覧にアイコンがあり、開くとホーム画面にする方法が表示されます。APK だけでは、ホーム、電源オン、スリープ解除は元のランチャーのままです。上のコマンドを実行するか、エージェントにもう一度任せてください（上書きインストールされ、設定は残ります）。
- **TCL で 多屏互动 の確認が出る**：右を押してから OK を押し、同意（Agree）を選びます。
- **Skyworth で `adb install` が `INSTALL_FAILED_USER_RESTRICTED` で失敗する**：`adb shell settings put global verifier_verify_adb_installs 0` を実行してから再度インストールします（ユーザーからの報告による。[エージェントガイド](site/en/agent.md#common-cases) を参照）。
- **ファームウェアがホーム画面アプリをインストールさせない**：コマンドの末尾に `-s guard` を付けるか、[aerial-guard.apk](https://aerial.qust.me/download/aerial-guard.apk) を使ってください。
- **`KEEP_ADB=1` の役割**：Xiaomi はスタンバイ後に ADB をオフにします。これを付けると Aerial が ADB をオンに戻すので、あとからアップデートできます。
- **アプリ、サブスクリプション、Xiaoai、アカウント、ケーブルテレビはそのまま？** はい。Aerial が置き換えるのはホーム画面だけです。元のホーム画面はアプリ一覧にあり（Xiaomi Video と PatchWall は 桌面 タイルから開けます）、Xiaoai、Xiaomi アカウント、ケーブルテレビはこれまでどおり使えます。起動時の広告はファームウェアによるものなので残ります。

## ホーム画面であり続ける仕組み

中国で販売されているテレビの多くは自社のホーム画面しか受け付けず、スタンバイ時にサードパーティのアプリを終了させます。そこで Aerial は、ユーザー補助サービスを使って標準のホーム画面が表示されるたびに切り替えて戻し（見ているのはどのウィンドウが前面にあるかだけで、画面の内容は一切見ません）、通知リスナーを使って終了させられたあとにシステムに再起動してもらいます（通知は一切読みません）。Android 6 の古い Xiaomi のテレビはホーム画面からウィンドウの変化を通知しないため、そこではガードがシステムの使用状況統計を読みます。また、一部の Coocaa や Skyworth のファームウェアが独自の方法で起動するホーム画面にも対応しています。素の Android TV ではこうした仕組みは不要で、Aerial は単にデフォルトのホーム画面になります。

<a id="supported-tvs"></a>

## 対応テレビ

✅ 動作する　⚠️ 制限付きで動作する　❌ まだ乗っ取れない。主にユーザーの報告にもとづきます。リンク先のメモ（中国語）に詳細があります。

| ブランド | 機種 | システム | 状態 |
|---|---|---|---|
| Xiaomi / Redmi | MiTV-MFTR0（Redmi X65 2026）、MFFU0、MTSU0（S65 Mini LED 2026） | Android 14 | ✅ 日常的に使用中（[MFTR0](docs/devices/xiaomi-mitv-mftr0.md)、[MFFU0](docs/devices/xiaomi-mitv-mffu0.md)、[MTSU0](docs/devices/xiaomi-mitv-mtsu0.md)） |
| Xiaomi | MiTV-ASTP0、ASTP1、NSOP0、MFTP0 | Android 9 | ✅ MFTP0 はシステムに終了させられたあと戻るまで 30〜60 秒かかる（[メモ](docs/devices/xiaomi-mitv-mftp0.md)） |
| Xiaomi | Mi TV 4 / 4A / 4X / 3S、Mi Box 4 | Android 6 | ✅ 1.43 以降。ホームから Aerial に戻るまで数秒かかる（[メモ](docs/devices/xiaomi-android6.md)） |
| Xiaomi | Mi TV 2（MiTV2-49） | Android 4.3 | ❌ Aerial Lite はインストールでき動作するが、ホームでシステムが Xiaomi のランチャーをコンポーネント指定で開くため、ホーム画面にできない（[#770](https://github.com/pengchujin/aerial-tv/issues/770)） |
| Huawei | M330 ボックス | Android 4.4 | ❌ Aerial Lite はインストールできるが、ファームウェアが起動のたびに自社ランチャーをデフォルトに戻す（[#850](https://github.com/pengchujin/aerial-tv/issues/850)） |
| Xiaomi（Fengmi） | Ultraman シリーズ プロジェクター | Android 9 | ✅（[メモ](docs/devices/xiaomi-ultraman-projector.md)） |
| TCL | MT5879（中国版）、98T8H | Android 11 | ✅ 日常的に使用中（[メモ](docs/devices/tcl-mt5879-cn.md)。98T8H：[#803](https://github.com/pengchujin/aerial-tv/issues/803)） |
| TCL | Q9L Pro | Android 14 | ⚠️ ADB は USB ケーブル経由のみ。テレビマネージャーで Aerial の自動起動を許可する必要あり。入力切替で映像が出ない（[#792](https://github.com/pengchujin/aerial-tv/issues/792)） |
| Thunderbird | 75S78A Plus（FFALCON T966D5） | Android 14 | ❌ ファームウェアが約 1 分ごとにユーザー補助をクリアするため、ホームを乗っ取れない。`boot_app_componentname` で起動時は Aerial になる（[メモ](docs/devices/ffalcon-t966d5.md)） |
| TCL | 75V2（m7221） | Android 8 | ✅ root なしで直接 Aerial で起動（[メモ](docs/devices/tcl-m7221-cn.md)） |
| TCL / FFALCON | 60A730U、55V2 Pro（x32a0）、65Q10E と Crane 6（m7642）、75S365C、75Q10L | Android 5.1–14 | ✅ 60A730U は guard 版のみ対応。55V2 Pro は adb インストールの前に `setprop persist.tcl.debug.installapk 1` が必要（[#620](https://github.com/pengchujin/aerial-tv/issues/620)） |
| FFALCON | 55S595C Ultra、Crane 6 2025 Ultra | Android 11+ | ❌ インストールはできるが、ホームを乗っ取れない（[メモ](docs/devices/tcl-mt9655-cn.md)） |
| ソニー | A80J（中国版）、BRAVIA VH1 | Android 10 / 12 | ✅ |
| ソニー | KD55X7000D（中国版） | Android 8 | ✅ 明るさはテレビ自身の設定でのみ変更可能（[#757](https://github.com/pengchujin/aerial-tv/issues/757)） |
| Skyworth / Coocaa | K3 Pro、75A7D | Android 11 | ✅ 75A7D の入力切替は 1.45 から動作 |
| Skyworth / Coocaa | 65G60、8R134 / 8R145、aigc 機種（C10 プロジェクター、P60、7T863） | Android 8–11 | ⚠️ 起動時にまず Coocaa のホームが開く、またはシステムに終了させられたあと戻るまで 20〜30 秒かかる（[メモ](docs/devices/skyworth-coocaa.md)） |
| Skyworth / Coocaa | 5S21（50K2、7626K2）、5S05（60U2）、8H66 H7 | Android 5.x | ⚠️ 起動後とスタンバイ後は Aerial になるが、ホームでは戻らない。入力が一覧に出ない |
| Skyworth / Coocaa | 8H57_H5 | Android 5.1 | ⚠️ 起動時に十数秒くるくる回ってから Aerial になる（[#785](https://github.com/pengchujin/aerial-tv/issues/785)） |
| Skyworth | 1AA46_D4（T963） | Android 9 | ✅（[#796](https://github.com/pengchujin/aerial-tv/issues/796)） |
| Skyworth | 7T641、7T634_P3H、75A6E、55A5F Pro（8R702）、8R721_A6FP | Android 13 | ❌ 黒い画面になる、またはウィンドウフォーカスがなくシステムに停止させられる（[#864](https://github.com/pengchujin/aerial-tv/issues/864)、[#839](https://github.com/pengchujin/aerial-tv/issues/839)） |
| Skyworth | Xiaopai P3（Q5001 / cupid-p2）ボックス | Android 10 | ⚠️ ホームを乗っ取るにはパッケージ名を変えたビルドが必要。起動時のブロードキャストはすべてブロックされる（[メモ](docs/devices/skyworth-p3-box.md)） |
| パナソニック | TH-55HX560C（Skyworth 製） | Android 8 | ⚠️ 入力が一覧に出ない |
| Konka | KKTV U86V9、2861 | Android 9 | ✅ U86V9 は明るさ調整が効かない（[メモ](docs/devices/konka-kktv-u86v9.md)） |
| LeTV | Super4 X55（f608） | Android 6 | ✅ インストール、起動時の自動起動、ホームの乗っ取りすべて可能（[#510](https://github.com/pengchujin/aerial-tv/issues/510)） |
| LeTV | Super3 X65（X65-168CQ） | Android 5.0.1 | ⚠️ インストールと表示はできるが、ファームウェアがホームでサードパーティのランチャーを終了させるため、乗っ取りは不安定（[メモ](docs/devices/letv-x3-65.md)） |
| Hisense / Vidda | VIDAA 機種 | Android 5–14 | ⚠️ ホームは動作するが、多くは起動時にまず Hisense のホームが開く。VIDAA C1 プロジェクターは乗っ取れない（[メモ](docs/devices/hisense-vidaa.md)） |
| Hisense | 65E52D | Android 8 | ✅ 直接 Aerial で起動（[#672](https://github.com/pengchujin/aerial-tv/issues/672)） |
| Hisense | 75E3F（VIDAA_TV、n62016） | Android 9 | ✅ 直接 Aerial で起動（[#847](https://github.com/pengchujin/aerial-tv/issues/847)） |
| Hisense | E5Q Pro | Android 11 | ✅（[#790](https://github.com/pengchujin/aerial-tv/issues/790)） |
| Hisense | VIDAA_TV（MSD6A348） | Android 4.4 | ✅ USB メモリから Aerial Lite をインストールし、ホームの確認で「常時」を選ぶ。明るさ調整なし（[#769](https://github.com/pengchujin/aerial-tv/issues/769)） |
| シャープ | LCD-50SU671A | Android 6 | ✅ ADB を開くには QuickShortcutMaker が必要 |
| OPPO | K9 | Android 9 | ⚠️ パッケージ名を変えたビルドが必要（[メモ](docs/devices/oppo-coloros-tv.md)） |
| XGIMI | Z7X、m7642 | Android 9 | ✅ Z7X は ADB を開くのにサードパーティ製ツールが必要（[メモ](docs/devices/xgimi-z7x.md)） |
| XGIMI | Lamp L1（m7642、pig_chivalrous） | Android 9 | ✅ 起動時にデフォルトホームが失われる。報告者による 2 つのルールでの対処法は [メモ](docs/devices/xgimi-z7x.md) に（[#767](https://github.com/pengchujin/aerial-tv/issues/767)） |
| XGIMI | Z6X 第 4 世代（XK03U / ironman / m7221） | Android 8 | ⚠️ HDMI 1 の一覧表示と切り替えは確認済み。映像出力は未テスト（[メモ](docs/devices/xgimi-xk03u-android8.md)） |
| XGIMI | H3、m7221 | Android 8 | ✅ ADB を開くのにサードパーティ製ツールが必要。起動時の引き継ぎとスクリーンセーバーは Z7X と同じ（[メモ](docs/devices/xgimi-h3.md)） |
| OBE | X7、X7D プロジェクター | Android 9 / 5.1 | ✅ HDMI 入力中のホームで Apple TV のホーム画面に移れる（[メモ](docs/devices/obe-x7.md)） |
| OBE | OBE_R3Ultra 高輝度プロジェクター（MT5879） | Android 11 | ✅ 開発者向けオプションには「USB デバッグ」しかなく、オンにすると 5555 番ポートで ADB が開く。ホームではまずファームウェアの Youku ランチャーに移り、約 13 秒後にガードが Aerial に戻す（[メモ](docs/devices/obe-r3-ultra.md)） |
| Tencent Aurora ボックス | 6 / 6SE / 7S | Android 10 / 14 | ✅ 6 は動作。⚠️ 7S は起動時に標準のホームのまま（[メモ](docs/devices/tencent-aurora-box.md)） |
| Dangbei | Box Max1、H5、HAQU K2（Dangbei OS） | Android 9 | ✅ |
| Dangbei | H3S ボックス（RK3566） | Android 11 | ⚠️ ファームウェアがホームでサードパーティのアプリを停止させる。Aerial は 7〜12 秒後に戻る（[#849](https://github.com/pengchujin/aerial-tv/issues/849)） |
| Dangbei | F3 プロジェクター | Android 6 | ❌ ファームウェアがサードパーティのランチャーを拒否し、ユーザー補助サービスもない（[#789](https://github.com/pengchujin/aerial-tv/issues/789)） |
| JMGO | S621 | Android 13 | ❌ ファームウェアがユーザー補助の設定への書き込みを拒否するため、ホームを乗っ取れない（[#793](https://github.com/pengchujin/aerial-tv/issues/793)） |
| エプソン | DTP9733 プロジェクター | Android 11 | ✅ |
| ZHIYING / RIGAL | HS350 プロジェクター（HiSilicon、YunOS） | Android 9 | ⚠️ ファームウェアがバックグラウンドに回ったアプリを停止させる。別のアプリからホームを押すと Aerial に戻るまで 10〜30 秒かかる。スクリプトを使わずにインストールした場合は 2 つの権限付与が必要（[#853](https://github.com/pengchujin/aerial-tv/issues/853)） |
| その他のボックス | CM311-1（改造 ATV）、PPTV K55U27、HK1 Box（S905X3） | Android 9 | ✅ HK1 Box：Aerial をデフォルトのホームに設定する（[#838](https://github.com/pengchujin/aerial-tv/issues/838)） |

- **Android 4.0〜4.4**：[Aerial Lite](#lite)（最初のリリース、ベータ版）。
- **非対応**：LG webOS、Samsung Tizen、Huawei Vision（HarmonyOS）、Android 4.0 より古いもの。
- **ADB を開けない**：古い LeTV のテレビ、Honor Vision、古い Konka の機種（タップするバージョン番号がない）。
- **動作が遅い・メモリが少ないテレビ**：1.48 以降、Aerial が自動で Lite エフェクト（不透明なパネル、ハイライトなし）を選びます。パーソナライズ → エフェクトで手動で選ぶこともできます。

<a id="adapt"></a>

### インストールしたけれど何かおかしい？ Aerial をあなたのテレビに合わせるのを手伝ってください

ホームで元のランチャーに戻る、スタンバイや再起動のあと元のランチャーになる、入力切替で違うポートが開く、明るさのスライダーが効かない、など。次のどちらかを選んでください。

**1. エージェントに任せる（いちばん簡単）**：パソコンでコマンドを実行できるエージェントに次の文を送ります。エージェントがテレビをテストし、その場で直せるもの（入力番号など）は直し、投稿前に確認できるレポートを書いてくれます。

```text
https://aerial.qust.me/en/adapt.md に従って、Aerial を私のテレビに合わせてください。
テレビの IP：<テレビの IP>
問題点：<例：ホームで元のランチャーに戻る / HDMI 2 を選ぶと HDMI 1 が開く / スタンバイ後に元のランチャーになる>

まずガイドを最後まで読み、コマンドを列挙するのではなく、私のパソコンで自分で実行してください。
スタンバイのテストやテレビの再起動の前には私に確認してください。システムアプリのアンインストールや無効化はしないでください。
完成したレポートを私に見せ、私が同意してから GitHub に投稿してください。
```

**2. スマホから送る**：コントロールセンター → パーソナライズで QR コードをスキャンし、スマホのページの一番下で「問題を報告」→「診断情報を表示」→「GitHub で報告」を選びます。機種とバージョンが [デバイスサポートフォーム](https://github.com/pengchujin/aerial-tv/issues/new?template=device.yml) に入力され、診断情報はコピーされるので貼り付けてください。

パソコンからは `curl -fsSL https://aerial.qust.me/tools/report.sh | TV=<テレビの IP> bash`（読み取りのみ）と `curl -fsSL https://aerial.qust.me/tools/selftest.sh | TV=<テレビの IP> bash`（ホームを押し、設定を開き、Aerial が戻ってくるかを確認）を実行し、両方の出力をフォームに貼り付けることもできます。診断情報にはアカウント、アドレス、Wi-Fi 名、アプリ一覧は含まれません。テレビで問題なく動いているなら、[うちのテレビで動いたフォーム](https://github.com/pengchujin/aerial-tv/issues/new?template=works.yml) でほかの人に教えてあげてください。

## 開発

JDK 17 と Android SDK 36 が必要です。

```bash
./gradlew assembleStandardRelease assembleGuardRelease
BUILD=1 KEEP_ADB=1 TV=<テレビの IP> tools/install.sh
```

コードについては [ARCHITECTURE.md](docs/ARCHITECTURE.md)、現状とルールについては [HANDOFF.md](docs/HANDOFF.md)、各テレビのメモは [docs/devices/](docs/devices/) を参照してください。

## ライセンス

[MIT](LICENSE)。Inter フォントは [SIL Open Font License](FONT-LICENSE-OFL.txt) です。天気予報用の中国の都市リストは [GeoNames](https://www.geonames.org)（CC BY 4.0）、天気データは [Open-Meteo](https://open-meteo.com) によるものです。

Aerial は独立したオープンソースプロジェクトであり、Apple、Xiaomi、TCL その他いかなる企業とも関係ありません。空撮映像は Apple に帰属し、Apple のサーバーから再生されます。今日の一枚は Bing から取得しています。
