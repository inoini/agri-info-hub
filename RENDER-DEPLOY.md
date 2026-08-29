# 農業情報ハブをRenderへ公開する設定

このプロジェクトは、ローカルではH2、RenderではNeon PostgreSQLを使用します。

Renderの環境変数には次の3項目を登録します。値はGitHubへ保存しないでください。

| Key | Value |
| --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://Neonのホスト/データベース名?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | NeonのRole名 |
| `SPRING_DATASOURCE_PASSWORD` | Neonのパスワード |

RenderではDocker Runtime、Freeプラン、`main`ブランチを使用します。起動ポートはRenderの`PORT`環境変数から自動取得します。

ローカル確認URLは `http://localhost:8082/` です。公開後はRenderが発行する `https://agri-info-hub-xxxx.onrender.com/` の形式のURLをスマートフォンでも開けます。
