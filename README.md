# Telegram Uploader

A post-build uploader that uploads artifacts generated during build process to Telegram chats.

# How to use

1. Create a [Telegram bot](https://core.telegram.org/bots#3-how-do-i-create-a-bot) and obtain its token.
2. Install the plugin on Jenkins server.
3. Configure bot token in Jenkins system settings (Manage Jenkins->Configure System->Telegram Uploader).
4. Add a "Upload artifacts to Telegram" post-build action to your Jenkins job after "Archive the artifacts" post-build action.
5. Configure the chat ID to upload build artifacts to. You could also check "Advanced" button for advanced settings.
6. Start a build and see the result.
