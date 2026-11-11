# NEXGRAM 2.0.0 — технічний стан і збірка

NEXGRAM — модифікація вихідного коду Telegram for Android (GPL-2.0), що зберігає його мережевий стек, авторизацію, чати, медіа та VoIP-підсистеми недоторканими.

## Що нового в 2.0.0 (реалізовано)

1. **Account Guard** (`org.telegram.nexgram.AccountGuardController` + `org.telegram.ui.AccountGuardActivity`)
   - Реальні RPC-виклики офіційного Telegram API: `account.getAuthorizations`, `account.getPassword`, `account.resetAuthorization`.
   - Список активних сесій: пристрій, застосунок, платформа, IP, країна, остання активність, поточна сесія.
   - Виявлення незнайомих сесій через локальний реєстр хешів сесій; нові сесії позначаються «НОВА» та записуються в локальний журнал подій.
   - Завершення сесії — тільки явна дія користувача з підтвердженням (офіційний `resetAuthorization`).
   - Статус двоетапної автентифікації + перехід до штатного `TwoStepVerificationActivity`.
   - Не блокує IP, не зберігає паролі чи сесії.
2. **Autonomous Intelligence + Identity Finder** (`org.telegram.nexgram.intelligence.*` + `org.telegram.ui.IdentityFinderActivity`)
   - Координатор за схемою з master prompt: Request Analyzer → Module Registry → Search Adapters → Result Aggregator → Source Verification → Error Handler → Report Generator.
   - Реальні адаптери публічних джерел: `TelegramPageAdapter` (жива публічна сторінка t.me), `GitHubAdapter` (публічний GitHub REST API), `ExternalSearchAdapter` (опціональний SerpAPI-сумісний провайдер, активний лише з ключем).
   - Кожен результат: джерело, посилання, дата, рівень достовірності (Підтверджено / Ймовірно / Непідтверджено).
   - Ключ зовнішнього API зберігається зашифрованим через Android Keystore (AES/GCM, `SecureKeyStore`).
   - Забезпечені заборони: не шукає приховані номери, приватні адреси, геолокацію, приватні переписки, злиті бази; не профілює людей за неперевіреними збігами.
3. **NEXGRAM Island — острівець повідомлень** (`org.telegram.nexgram.NexgramIsland`)
   - Анімований банер у застосунку при новому повідомленні, поки застосунок відкритий і в фокусі.
   - Працює через офіційний `NotificationCenter.didReceiveNewMessages` та штатний `MessagesController`-потік.
   - Поважає налаштування звуку/без звуку чату через офіційний `NotificationsController.isGlobalNotificationsEnabled`.
   - НЕ дублює і не замінює системні сповіщення Telegram; це власний UI-елемент вікна NEXGRAM.
   - Тап відкриває чат (штатний `ChatActivity`).
4. **NEXGRAM Settings 2.0** (`NEXGRAMSettingsActivity`)
   - Акцент (6 кольорів), острівець + його анімації, зменшення анімацій NEXGRAM — усе збережено у `SharedPreferences` і все реально впливає на поведінку.
   - Секції Безпека (→ Account Guard) та Розумний пошук (→ Identity Finder).
   - Про NEXGRAM + політика модуля Identity Finder.
5. **Інтеграція**: `LaunchActivity` (attach/detach острівця), `SettingsActivity` (оновлений запис NEXGRAM), теми 1.x збережено.
6. **Збірка**: виправлено google-services.json для пакетів `com.nexgram.android*` (модулі застосунку; бібліотека лишає `org.telegram.messenger` за namespace), версія `2.0.0` (code 3), Gradle heap знижено з 8 ГБ до 2.56 ГБ для телефонів.

## Вимоги до середовища збірки

| Компонент | Вимога |
|---|---|
| Gradle Wrapper | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin Gradle Plugin | 2.1.0 |
| Java | 17+ |
| compileSdk / targetSdk | 36 / 36 |
| Build Tools | 36.0.0 |
| NDK | 27.2.12479018 |
| CMake | 3.22.1 |
| minSdk | 23 |

## Три способи зібрати APK

### 1. AndroidIDE (на пристрої)
1. Відкрий проєкт у AndroidIDE (папка проєкту має містити корінь Telegram-Android-master).
2. Встанови NDK **27.2.12479018** (не 28.2!) через SDK Manager AndroidIDE.
3. `./gradlew :TMessagesProj_App:assembleAfatDebug` (variants: afat/bundleAfat × debug/standalone/release).
4. Якщо aapt2 не запускається: `./gradlew -Pandroid.aapt2FromMavenOverride=$HOME/.androidide/aapt2 ...`.

### 2. GitHub Actions (безкоштовно, з NDK на сервері)
1. Запуш це дерево у свій GitHub-репозиторій (з .gitmodules і gitlink-ами субмодулів).
2. Додай секрети `TG_API_ID` і `TG_API_HASH`.
3. Workflow `.github/workflows/build-apk.yml` збере `assembleAfatDebug` і віддасть APK у артефактах.

### 3. Termux (перевірка компіляції без APK)
- `-PnoNative` прибирає нативну частину (без NDK); APK без нативних бібліотек не запуститься — це лише перевірка Java/Kotlin/ресурсів.
- aapt2 для ARM64: статичний aapt2 35.0.2 (Lzhiyong/sdk-tools) через qemu-user (обгортка `~/sdk-tools/aapt2-wrapper.sh`, `-Pandroid.aapt2FromMavenOverride=`).

## API та Firebase

1. Отримай власні `api_id`/`api_hash` на https://my.telegram.org/apps.
2. Впишись локально у `BuildVars.java` (APP_ID/APP_HASH) — не коміть їх і не надсилай у чат.
3. Firebase: власний `google-services.json` для `com.nexgram.android` (+ `.beta`, `.web`) потрібен для push-нотифікацій через FCM. Статичні значення upstream (проєкт tmessages2) лишаються як у GPL-оригіналі; без власного проєкту Firebase пуші не працюватимуть.

## Статус перевірки (оновлюється)

- [x] Розпакування та аналіз NEXGRAM 1.4.0 і чистого Telegram master (7be672ea).
- [x] Відновлення всіх 19 Git-субмодулів на зафіксованих SHA (0 розбіжностей).
- [x] Брендинг, applicationId `com.nexgram.android`, версія 2.0.0.
- [x] Account Guard: реальні TL RPC + UI + журнал (код).
- [x] Intelligence: координатор + 3 адаптери + UI (код).
- [x] NEXGRAM Island: NotificationCenter + вікно + налаштування (код).
- [x] google-services.json виправлений для нових package ID.
- [x] Gradle-компіляція Java/Kotlin/ресурсів: **BUILD SUCCESSFUL** (`:TMessagesProj_App:compileAfatDebugJavaWithJavac`, 209 задач, Termux + qemu-aapt2, режим `-PnoNative`).
- [ ] Повна нативна збірка (AndroidIDE з NDK 27.2 або GitHub Actions).
- [ ] Встановлення та runtime-тест на Realme 12 4G / Android 16.
- [ ] NEXGRAM Nearby (BLE/Wi-Fi Direct) — наступний етап.
- [ ] Дизайн екранів дзвінків (VoIP-тема) — наступний етап.

**Проміжний висновок:** дерево тепер є повним вихідним кодом (усі субмодулі на місці). Компіляція Java/Kotlin/ресурсів **перевірена успішно** (Gradle, BUILD SUCCESSFUL). APK не оголошується готовим, доки не буде виконано нативну збірку і APK не буде встановлено на пристрій.
