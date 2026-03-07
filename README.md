# 🛸 EVE Structure Monitor Discord Bot

A Discord bot that monitors your EVE Online corporation's structures for attacks and sends detailed alerts with role pings.

---

## 📋 What It Does

- Polls the EVE ESI API every 5 minutes for structure attack notifications
- Detects: **under attack**, **shields gone**, **armor gone**, **structure destroyed**, **unanchoring**
- Sends a rich Discord embed with:
  - Structure name, type (Fortizar, Athanor, etc.), and location
  - Shield/armor health bars
  - Attacker name, corp, and alliance
  - Reinforce timer end time
  - @role ping to notify your team
- Also checks structure states every 15 minutes for reinforce timers

---

## 🚀 Setup Guide (Step by Step)

### Prerequisites
- A GitHub account
- A Railway account (free at railway.app)
- Access to a Discord server where you're an admin
- A director-level EVE character for your corporation

---

### Step 1: Create a Discord Bot

1. Go to [https://discord.com/developers/applications](https://discord.com/developers/applications)
2. Click **"New Application"** → name it (e.g., "Structure Monitor")
3. Go to the **"Bot"** tab on the left
4. Click **"Add Bot"** → **"Yes, do it!"**
5. Under "Token", click **"Reset Token"** then **Copy** it — save this as `DISCORD_BOT_TOKEN`
6. Scroll down and enable these **Privileged Gateway Intents**:
   - ✅ Message Content Intent
7. Go to **"OAuth2" → "URL Generator"**
8. Check these scopes: `bot`
9. Check these bot permissions: `Send Messages`, `Embed Links`, `Mention Everyone`
10. Copy the generated URL and paste it in your browser to invite the bot to your server

### Step 2: Get Discord Channel and Role IDs

**Enable Developer Mode in Discord:**
- Discord Settings → Advanced → Developer Mode: ON

**Get Channel ID:**
- Right-click the channel you want alerts in → **"Copy Channel ID"**
- Save as `DISCORD_ALERT_CHANNEL_ID`

**Get Role ID:**
- Server Settings → Roles → right-click the role to ping → **"Copy Role ID"**
- Save as `DISCORD_ALERT_ROLE_ID`
- (Create a new role like "@Structure Alert" if you don't have one)

---

### Step 3: Register an EVE ESI Application

1. Go to [https://developers.eveonline.com/](https://developers.eveonline.com/)
2. Log in with your EVE account
3. Click **"Create New Application"**
4. Fill in:
   - **Name**: Structure Monitor Bot
   - **Description**: Monitors corp structures for attacks
   - **Connection Type**: Authentication & API Access
   - **Permissions/Scopes** — add ALL of these:
     ```
     esi-corporations.read_structures.v1
     esi-characters.read_notifications.v1
     esi-universe.read_structures.v1
     ```
   - **Callback URL**: `https://localhost/callback` (we won't actually use this)
5. Click **Create Application**
6. On the next page, copy:
   - **Client ID** → save as `EVE_CLIENT_ID`
   - **Secret Key** → save as `EVE_CLIENT_SECRET`

---

### Step 4: Set Up the Director Login Page

The bot includes a built-in web page that handles EVE SSO login for you — no command line needed. Directors just visit a URL, click login, and get their token displayed ready to copy.

**First, add your Railway app's URL to your EVE Developer Application:**

1. Go back to [https://developers.eveonline.com/](https://developers.eveonline.com/) and open your app
2. Find the **Callback URL** field and set it to:
   ```
   https://YOUR-APP-NAME.railway.app/auth/callback
   ```
   (You'll know your Railway app URL after Step 6 — you can come back and update this)
3. Save the application

**Add one more Railway environment variable:**

| Variable | Value |
|---|---|
| `EVE_ESI_CALLBACK_URL` | `https://YOUR-APP-NAME.railway.app/auth/callback` |

**Then, to generate a token for any director:**
1. Send them this URL: `https://YOUR-APP-NAME.railway.app/auth`
2. They click **"Login with EVE Online"**
3. They log in with their EVE director character
4. The page shows their **Character ID** and **Refresh Token** — click to copy each one
5. Paste those values into Railway as `EVE_CHARACTER_ID` and `EVE_REFRESH_TOKEN`

That's it. You can do this for multiple directors or re-run it any time you need a fresh token.

**Get your Corporation ID:**
1. Go to [https://evewho.com/](https://evewho.com/) and find your corporation
2. The number in the URL is your corp ID → save as `EVE_CORPORATION_ID`

---

### Step 5: Deploy to GitHub

1. [Create a new GitHub repository](https://github.com/new)
   - Name: `eve-structure-bot`
   - Visibility: **Private** (recommended — your code will be here)
   - Don't initialize with README (you have one already)

2. On your computer, open Terminal (Mac/Linux) or Git Bash (Windows)

3. Run these commands (replace `yourusername` with your GitHub username):
```bash
git init
git add .
git commit -m "Initial commit: EVE Structure Monitor Bot"
git branch -M main
git remote add origin https://github.com/yourusername/eve-structure-bot.git
git push -u origin main
```

---

### Step 6: Deploy to Railway

1. Go to [https://railway.app](https://railway.app) and sign up/log in
2. Click **"New Project"**
3. Select **"Deploy from GitHub repo"**
4. Connect your GitHub account and select your `eve-structure-bot` repository
5. Railway will detect the Dockerfile and start building

**Set Environment Variables:**
6. In your Railway project, click on your service
7. Go to the **"Variables"** tab
8. Add each of these (click "+ New Variable" for each):

| Variable Name | Value |
|---|---|
| `DISCORD_BOT_TOKEN` | Your Discord bot token |
| `DISCORD_ALERT_CHANNEL_ID` | Your Discord channel ID |
| `DISCORD_ALERT_ROLE_ID` | Your Discord role ID |
| `EVE_CLIENT_ID` | Your EVE ESI client ID |
| `EVE_CLIENT_SECRET` | Your EVE ESI client secret |
| `EVE_REFRESH_TOKEN` | Your EVE refresh token |
| `EVE_CORPORATION_ID` | Your corporation's numeric ID |
| `EVE_CHARACTER_ID` | Your director character's numeric ID |
| `POLL_INTERVAL_MS` | `300000` (5 minutes) |

9. After adding all variables, Railway will automatically redeploy
10. Check the **"Deploy Logs"** tab — you should see:
    ```
    EVE Structure Monitor Bot Starting...
    Discord bot connected successfully!
    Structure cache updated: X structures found
    ```

---

## 🔧 Configuration

| Variable | Description | Default |
|---|---|---|
| `POLL_INTERVAL_MS` | How often to check for new notifications | `300000` (5 min) |
| `DISCORD_BOT_TOKEN` | Discord bot token | Required |
| `DISCORD_ALERT_CHANNEL_ID` | Channel to send alerts to | Required |
| `DISCORD_ALERT_ROLE_ID` | Role to @ mention | Required |
| `EVE_CLIENT_ID` | EVE ESI application client ID | Required |
| `EVE_CLIENT_SECRET` | EVE ESI application secret | Required |
| `EVE_REFRESH_TOKEN` | EVE director refresh token | Required |
| `EVE_CORPORATION_ID` | Corporation numeric ID | Required |
| `EVE_CHARACTER_ID` | Director character numeric ID | Required |

---

## 🔔 Alert Types

| Notification | Embed Color | When Triggered |
|---|---|---|
| Under Attack | 🟡 Yellow | Structure is taking damage |
| Shields Gone | 🟠 Orange | Shields depleted, armor timer started |
| Armor Gone | 🔴 Red | Armor depleted, hull timer started |
| Structure Destroyed | ☠️ Dark Red | Structure has been destroyed |
| Unanchoring | 🟣 Purple | Structure is being unanchored |

---

## 🛠️ Troubleshooting

**Bot not sending alerts:**
- Check Railway logs for errors
- Verify all environment variables are set correctly
- Make sure the bot has permissions in the Discord channel
- Verify the bot was invited to the server with correct permissions

**ESI token errors:**
- Your refresh token may have expired (rare, but possible if not used for 6+ months)
- Repeat Step 4 to get a new refresh token

**"Could not find channel" error:**
- Make sure Developer Mode is enabled in Discord when copying the channel ID
- Verify the bot is in the same server as the channel

**No structures showing up:**
- Confirm `EVE_CORPORATION_ID` is correct (numeric ID, not the ticker)
- Ensure the director character actually has director roles in-game
- Check the ESI scopes include `esi-corporations.read_structures.v1`

---

## 📝 Notes

- ESI notifications have up to a ~5 minute delay from EVE server time
- The bot only polls as fast as `POLL_INTERVAL_MS` — don't set this below 60 seconds
- The refresh token will automatically renew itself with each use
- Structure names require an authenticated ESI call — make sure your scopes include `esi-universe.read_structures.v1`

---

## 🤝 Required ESI Scopes (Summary)

Make sure your EVE developer application has these scopes:
- `esi-corporations.read_structures.v1` — Lists corp structures and their states
- `esi-characters.read_notifications.v1` — Reads attack notifications
- `esi-universe.read_structures.v1` — Gets structure names/locations
