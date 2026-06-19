# yt-dlp Studio (Java GUI)

![yt-dlp Studio Logo](https://raw.githubusercontent.com/your-username/yt-dlp-studio/main/app.png)

## 概要

yt-dlp Studio は、人気のコマンドラインツール `yt-dlp` を Windows 環境で直感的に操作するためのモダンなグラフィカルユーザーインターフェース (GUI) アプリケーションです。Java Swing と FlatLaf を使用して開発されており、動画や音声のダウンロード、メタデータ埋め込み、アルバムアート、歌詞の取得など、一連のメディアアーカイブ作業を効率化します。

## 特徴

*   **モダンなUI**: FlatLaf による洗練されたダーク/ライトテーマ対応のインターフェース。
*   **並列ダウンロード**: 複数のURLを同時に処理し、効率的なダウンロードを実現。
*   **リアルタイム進捗表示**: 各ダウンロードの進捗状況、速度、残り時間をリアルタイムで表示。
*   **豊富なダウンロードオプション**: 動画品質、音声フォーマット（MP3, WAV, FLAC, AACなど）、字幕、メタデータ、チャプター、サムネイル埋め込みなど、詳細な設定が可能。
*   **ファイル名テンプレート**: カスタマイズ可能なファイル名フォーマットとプレビュー機能。
*   **クリップボード監視**: コピーされたURLを自動検出し、ダウンロードキューに簡単に追加。
*   **履歴管理**: ダウンロード履歴を保存・検索し、URLの再利用も可能。
*   **yt-dlp/ffmpeg自動検出**: 必要な外部ツールを自動的に検出し、パス設定を簡素化。
*   **スタンドアロン実行ファイル**: `jpackage` により、Java実行環境がなくても動作する `.exe` ファイルとして配布。
*   **エラーの自動解析**: yt-dlpのエラーメッセージを解析し、分かりやすい日本語で表示。

## スクリーンショット

（ここにアプリケーションのスクリーンショットを挿入予定）

## インストール

### 実行可能ファイル (.exe) の使用

1.  [最新リリース](https://github.com/your-username/yt-dlp-studio/releases) ページから `yt-dlp-studio-X.Y.Z.exe` をダウンロードします。
2.  ダウンロードした `.exe` ファイルを実行します。
3.  `yt-dlp.exe` と `ffmpeg.exe` がアプリケーションと同じディレクトリ、またはシステムPATH上に存在することを確認してください。これらは [yt-dlp GitHub](https://github.com/yt-dlp/yt-dlp/releases) および [ffmpeg 公式サイト](https://ffmpeg.org/download.html) からダウンロードできます。

### ソースコードからのビルド

#### 前提条件

*   Java Development Kit (JDK) 21 以降
*   Apache Maven 3.8 以降
*   `yt-dlp.exe` および `ffmpeg.exe` (PATHが通っているか、プロジェクトルートに配置)

#### ビルド手順

1.  リポジトリをクローンします。
    ```bash
    git clone https://github.com/your-username/yt-dlp-studio.git
    cd yt-dlp-studio
    ```
2.  Maven を使用してプロジェクトをビルドします。
    ```bash
    mvn clean package
    ```
3.  `jpackage` を使用してネイティブインストーラーを生成します。
    ```bash
    # Windows (exe/msi)
    jpackage \
      --input target/lib \
      --name yt-dlp-studio \
      --main-jar yt-dlp-studio-X.Y.Z.jar \
      --main-class YtDlpStudio \
      --type exe \
      --app-version X.Y.Z \
      --vendor "YtDlp Studio" \
      --description "Modern yt-dlp GUI for Windows" \
      --icon src/main/resources/app.ico \
      --win-menu \
      --win-shortcut
    ```
    *`X.Y.Z` は現在のバージョン番号に置き換えてください。*
    *`app.ico` は `src/main/resources` ディレクトリに配置する必要があります。*

## 使用方法

1.  アプリケーションを起動します。
2.  「ダウンロード」タブで、ダウンロードしたい動画や音声のURLをテキストエリアに貼り付けます（複数行可）。
3.  ダウンロード形式（動画/音声、品質など）を選択します。
4.  保存先フォルダを指定します。
5.  「キューに追加」ボタンをクリックすると、ダウンロードが開始されます。
6.  「履歴」タブで過去のダウンロード履歴を確認できます。
7.  「設定」タブで、ファイル名テンプレート、速度制限、プロキシなどの詳細設定を変更できます。

## 開発者向け情報

### プロジェクト構造

```
.github/
├── workflows/
│   └── maven.yml                  # GitHub Actions for CI/CD
src/
├── main/
│   ├── java/
│   │   └── YtDlpStudio.java       # メインアプリケーションコード
│   └── resources/
│       └── app.png                # アプリケーションアイコン
│       └── app.ico                # Windows用アイコン
├── test/
│   └── java/
│       └── YtDlpStudioTest.java   # ユニットテスト (予定)
.gitignore
pom.xml                            # Mavenプロジェクト設定ファイル
README.md                          # このファイル
LICENSE                            # ライセンス情報
```

### 依存関係

*   `com.formdev:flatlaf`: モダンなLook and Feel
*   `com.google.code.gson:gson`: JSON処理

### 貢献

バグ報告や機能提案は、GitHubのIssuesをご利用ください。プルリクエストも歓迎します。

## ライセンス

このプロジェクトは [MIT License](LICENSE) の下でライセンスされています。

## 謝辞

*   [yt-dlp](https://github.com/yt-dlp/yt-dlp) 開発チーム
*   [FlatLaf](https://www.formdev.com/flatlaf/) 開発チーム
*   [Google Gson](https://github.com/google/gson) 開発チーム

---

**作成者:** Manus AI
**日付:** 2026年6月18日
