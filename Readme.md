## Installation & Setup Guide

### 1. Prerequisites
* Ensure **Wireless ADB** is enabled on your device before launching the app.

### 2. Initial Configuration
* **Authorize ADB:** Upon opening the app for the first time, accept the ADB authentication prompt on your device screen.
* **Background Persistence:** Go to the **Settings Menu** and ensure the **"Disable Autostart"** toggle is **UNCHECKED**. This is critical for the service to run reliably in the background.

### 3. Network & Tunnel Setup
Choose the configuration that matches your connection type:

* **Option A: Dedicated Wi-Fi Hotspot (Recommended)**
  * Keep the **Sing-box Proxy** disabled.
  * Directly enable your preferred tunnel (**Zrok** or **Cloudflared**).

* **Option B: Public / Shared Wi-Fi**
  * Toggle the **"Public"** switch at the top right of the dashboard.
  * Go to **Daemon View** and verify that the **Sing-box Proxy Daemon** is running.
  * Once verified, enable your preferred tunnel (**Zrok** or **Cloudflared**).
