# Daily Log

A personal, phone-friendly web app for logging my day: water, food, fibre, Gudlax, stool, medicines, symptoms, sleep, weight and notes.

## How it works
- One file: `index.html`. No server, no sign-in.
- Type a short line like `water 200` or `idli 3 sambar` and it goes into the right category.
- Water is added up against a 2500 ml target.
- "Copy Today" and "Copy Last 3 Days" make a text summary to paste into Claude.
- The day changes at 4 AM.
- Works as a home-screen app on the phone.

## Where the data lives
Only in the browser on the phone. It is not in this repo.
Use **Backup > Export JSON Backup** often, and keep the file somewhere safe.

## Hosting
Hosted as a free static site (Render or Netlify). Publish directory: the repo root. No build step.
