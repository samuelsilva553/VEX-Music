# VEX Music (Android) – Fase 1

Estrutura, Gradle, permissões e tela inicial que lê o MediaStore.

## Compilar no computador
1. Instale o Android Studio (JDK 17 já vem junto).
2. File > Open e escolha esta pasta. Aguarde o Gradle sincronizar.
3. Run (▶) com o celular em Depuração USB, ou Build > Build APK(s).
4. APK: `app/build/outputs/apk/debug/app-debug.apk`

## Compilar sem computador (GitHub)
1. Crie um repositório e envie estes arquivos (mantenha a pasta `.github`).
2. Aba Actions > "Gerar APK" > Run workflow.
3. Baixe o artefato `vex-music-apk` e instale no celular.

## Erros comuns
- "SDK location not found": o Android Studio cria o `local.properties` sozinho; abra o projeto por ele.
- "Unsupported Java": use JDK 17 (Settings > Build > Gradle > Gradle JDK).
- Gradle < 8.7: o Android Studio atualiza ao aceitar a sugestão; na linha de comando use `gradle wrapper --gradle-version 8.7`.
