## MetaLens

Talk to ChatGPT, take pictures, and analyze what you see through your Meta Glasses.

> 📥 **Quick download**
> - **Android**: Download the latest `.apk`, then follow the installation steps below.
> - **iOS**: Work in progress.

## UI Preview

<p align="center">
  <img src="docs/screenshots/home.jpg" width="24%" alt="Home" />
  <img src="docs/screenshots/history.jpg" width="24%" alt="History" />
  <img src="docs/screenshots/settings.jpg" width="24%" alt="Settings" />
  <br />
  <img src="docs/screenshots/conversation.jpg" width="24%" alt="Conversation" />
  <img src="docs/screenshots/analysis.jpg" width="24%" alt="Analysis" />
  <img src="docs/screenshots/streaming.jpg" width="24%" alt="Streaming" />
</p>

## Application features

### 🏠 Home
The landing screen with access to live conversations with ChatGPT, live streaming, and picture analysis through your smart glasses.

### 🕘 History
A conversation archive that lets you review your previous conversations session.

### ⚙️ Settings
Configuration for connecting your smart glasses and setting up your ChatGPT access.

### 💬 Conversation
A hands-free voice conversation view with a live transcript.

### 📷 Analysis
Take a picture and get an on-screen description/analysis of what the glasses camera sees.

### 📡 Streaming
Live stream what the glasses camera sees for continuous, hands-free assistance.

## Installing MetaLens AI (Important Information)

MetaLens AI uses a new Meta SDK that hasn’t been released to the public yet (expected in Q1 2026). 
For this reason, the app is not available on the Google Play Store or any other app store yet.
To try MetaLens AI, you’ll need to install it manually by downloading the APK file to your Android phone.
You’ll also need to enable Developer Mode in the Meta AI app. Don’t worry this guide explains everything step by step in the next section.

##  Quick Setup Checklist

To run MetaLens AI app, make sure the following checklist is completed:
- Ray-Ban Meta Smart Glasses powered on
- Android 12 or newer installed on your phone
- Stable internet connection
- Bluetooth enabled on your phone
- Meta AI app installed on your phone
- Developer Mode enabled in the Meta AI app ⚠️ 
- Glasses connected to the Meta AI app
- MetaLens AI connected to Meta AI app Settings -> "Connect my glasses" status Connected. Glasses visible on the "Connected devices" list.
- OpenAI API key set in Settings
- Check connection button i AI Settings shows “Connection OK”.
- All the monitos requiring access to camera bluetooth and are confirmed
- More details about the critical points in this list are explained in the instructions below.

## Detailed Installation Steps

Critical Steps to Run MetaLens on Your Android Phone.

1) Developer Mode enabled in the Meta AI app:

> ⚠️ **Developer Mode Required**  - Before You Start Instalation MetLens AI
> Your Meta glasses must have **Developer Mode enabled** in the Meta AI app before the MetaLens AI app can connect to them.
> **How to enable:**  
> 1. Open **Meta AI** app on your phone  
> 2. Go to **Settings** → **App Info**  Note: This is not the glasses settings (found in the top-left ☰ menu, then go to Settings at the bottom of the menu.).
> 3. Tap **App version** number **five times quickly** — this reveals the Developer Mode toggle  
> 4. Enable the **Developer Mode** toggle  
> 5. Tap **Enable** to confirm
>
> <img src="docs/images/meta-view-develop-mode.png" alt="Meta AI Developer Mode" width="50%" />
>
> See [Meta Wearables Setup Guide](https://wearables.developer.meta.com/docs/getting-started-toolkit) for detailed instructions.

## Download the MetaLens .APK file to your phone.
> No Google Play Store - installation from APK (see why above) 
> Download .APK file

1. On your phone, enable installing apps from unknown sources.
2. Open the APK and complete the installation.
3. Open **MetaLens** and go to settings click "Connect my glasses", turn the toggle on, and connect to your glasses.
4. Open the OpenAI API Key settings and enter your OpenAI API key, your API key should start with `sk-proj-`.
5. Click Connection Check; if the provided API key is correct, you should see a “Connection OK” status.
6. Go to the Home screen—if the buttons are not grayed out, you’re good to go.


## Privacy and Security
- Audio text and video data are used only for AI processing using your own OpenAI account and API key
- No user data is uploaded to any third party except OpenAI, which is required for AI processing.
- Your OpenAI API key is stored locally on your phone, is never logged, and is shared only with the OpenAI platform to enable AI processing.
- API communications use HTTPS encryption.
- The app complies with Meta’s privacy policies.

## Support Development

If this project is useful to you, you can support its development here:  
→ [Buy Me a Coffee](https://buymeacoffee.com/przemek_nowicki)

## License

This project is licensed under the [MIT License](LICENSE).