# DECISION: 平文通信(cleartext)を意図的に許可する

対象: `network_security_config.xml`
移設元: 旧README.md「既知の要修正ポイント」

## 決定事項

`network_security_config.xml`は`base-config cleartextTrafficPermitted="true"`にしてある。

## 経緯

2026-08、`https://`→`http://`へのリダイレクト先で「CLEARTEXT communication ... not
permitted」となる不具合があり、その修正として設定した。

## 理由(なぜ「不具合」ではなく意図的な設定か)

**汎用ブラウザとしての意図的な設定である。** Chrome/Firefox等の一般的なブラウザアプリも
同様に平文(http)通信を許可しており、危険性の警告や判断はブラウザ自身ではなく
ブラウザのUI側(アドレスバーの鍵アイコン等)の役割としている。このプロジェクトも
「任意のURLを開けるブラウザ」である以上、http自体を通信レベルで拒否するのは
ブラウザとしての基本機能を損なう。

## 注意

ここを`false`に戻すと、httpサイトが軒並み開けなくなる。「セキュリティ強化」のつもりで
安易に変更しないこと。もし変更するなら、それは「汎用ブラウザとしての方針転換」に
相当するため、この決定記録ごと更新すること。
