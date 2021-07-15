# Jenkins Telegram Uploader

A Jenkins post-build uploader plugin that uploads artifacts generated during build process to Telegram chats.

## How to use

### Installing

1. Create a [Telegram bot](https://core.telegram.org/bots#3-how-do-i-create-a-bot) and obtain its token.
2. Download .hpi file for [latest release](https://github.com/3cky/jenkins-telegram-uploader-plugin/releases) of the plugin and install it into your Jenkins ("Manage Jenkins"->"Plugin Manager"->"Advanced"->"Upload Plugin").
3. Configure bot token in Jenkins system settings ("Manage Jenkins"->"Configure System"->"Telegram Uploader").

### Freestyle job

1. Add a "Upload artifacts to Telegram" post-build action to your Jenkins job after "Archive the artifacts" post-build action.
2. Configure the chat ID to upload build artifacts to. You could also check "Advanced" button for advanced settings.

### Pipeline job

```
archiveArtifacts artifacts: 'output/*.apk'

telegramUploader chatId: '87654321', forwardChatIds: '-12345678', filter: 'output/*.apk', caption: "Job '${env.JOB_NAME}", silent: true, failBuildIfUploadFailed: false
```

### About the Telegram chat IDs

Plugin can upload artifacts to chat IDs of non-bot users, groups or channels.

You could use [@getidsbot](https://t.me/getidsbot) Telegram bot to obtain these chat IDs.

In case of the uploading to the channel or group please don't forget to add your Telegram bot to it at first.
