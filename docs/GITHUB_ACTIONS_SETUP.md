# GitHub Actions ワークフロー設定ガイド

## 概要

このプロジェクトでは、GitHub Actionsを使用してWindows環境で自動的にアプリケーションをビルドし、インストーラー（exe/msi）を生成・リリースするワークフローが設定されています。

## ワークフロー: Build Windows Installer

### ファイル位置
`.github/workflows/build-windows-installer.yml`

### トリガー条件

このワークフローは以下の条件で自動実行されます：

1. **mainブランチへのプッシュ**: `main` ブランチにコードがプッシュされたとき
2. **developブランチへのプッシュ**: `develop` ブランチにコードがプッシュされたとき
3. **タグの作成**: `v*` 形式のタグが作成されたとき（例: `v5.1.0`）
4. **手動トリガー**: GitHub UIから手動で実行可能

### 実行ステップ

1. **コードのチェックアウト**: リポジトリのコードを取得
2. **Java 21のセットアップ**: Temurin JDK 21をインストール
3. **Mavenビルド**: `mvn clean package` でJARファイルを生成
4. **jpackage準備**: ビルド成果物を整理
5. **EXEインストーラー生成**: Windows用の実行可能インストーラーを生成
6. **MSIインストーラー生成**: Windows用のMSIインストーラーを生成
7. **リリース作成**（タグの場合）: GitHub Releaseを作成し、インストーラーをアップロード
8. **アーティファクトアップロード**（タグ以外）: ビルド成果物を一時保存

### リリース手順

Windows用のインストーラーを生成してGitHub Releaseにアップロードするには、以下の手順に従ってください：

#### ステップ1: ローカルで変更をコミット

```bash
git add .
git commit -m "Update version to 5.2.0"
```

#### ステップ2: タグを作成してプッシュ

```bash
git tag v5.2.0
git push origin main
git push origin v5.2.0
```

#### ステップ3: GitHub Actionsの実行を確認

1. GitHubのリポジトリページを開きます。
2. 「Actions」タブをクリックします。
3. 「Build Windows Installer」ワークフローが実行されているか確認します。
4. ワークフローが完了するまで待機します（通常5～10分）。

#### ステップ4: GitHub Releaseを確認

1. リポジトリの「Releases」セクションを開きます。
2. 新しいリリース（例: `v5.2.0`）が作成されていることを確認します。
3. リリースページに以下のファイルが含まれていることを確認します：
   - `yt-dlp-studio-5.2.0.exe`
   - `yt-dlp-studio-5.2.0.msi`
   - `yt-dlp-studio-5.2.0.jar`

### トラブルシューティング

#### ワークフローが失敗する場合

1. **ログを確認**: GitHub Actions の実行ログを確認し、エラーメッセージを確認します。
2. **JDKバージョン**: Java 21がインストールされていることを確認します。
3. **app.icoファイル**: `src/main/resources/app.ico` が存在することを確認します。

#### インストーラーが生成されない場合

1. **jpackageの互換性**: jpackageはJDK 16以上で利用可能です。
2. **メインクラス**: `pom.xml` の `<mainClass>` が正しく設定されていることを確認します。
3. **依存関係**: すべての依存ライブラリが正しくシェードされていることを確認します。

### カスタマイズ

#### バージョン番号の更新

`pom.xml` の `<version>` タグを更新してください：

```xml
<version>5.2.0</version>
```

#### インストーラーの設定変更

`.github/workflows/build-windows-installer.yml` の `jpackage` コマンドを編集してください：

```powershell
& "$javaHome\bin\jpackage.exe" `
  --input target/jpackage-input `
  --name yt-dlp-studio `
  --main-jar yt-dlp-studio-5.1.0.jar `
  --main-class YtDlpStudio `
  --type exe `
  --app-version 5.1.0 `
  # その他のオプション...
```

---

**作成者:** Manus AI
**日付:** 2026年6月19日
