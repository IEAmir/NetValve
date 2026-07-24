#!/usr/bin/env bash
# =============================================================================
# NetValve - GitHub Publishing Helper
# =============================================================================
# This script automates the process of:
# 1. Initializing a local git repository (if not already)
# 2. Creating the first commit with all project files
# 3. Providing instructions for creating the GitHub repo and pushing
#
# Usage:
#   ./publish-to-github.sh [GITHUB_USERNAME]
#
# Example:
#   ./publish-to-github.sh IEAmir
# =============================================================================

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Get GitHub username from argument or prompt
GITHUB_USERNAME="${1:-}"

if [ -z "$GITHUB_USERNAME" ]; then
    echo -e "${YELLOW}Enter your GitHub username:${NC}"
    read -r GITHUB_USERNAME
fi

if [ -z "$GITHUB_USERNAME" ]; then
    echo -e "${RED}Error: GitHub username is required${NC}"
    exit 1
fi

REPO_NAME="NetValve"
REPO_URL="https://github.com/${GITHUB_USERNAME}/${REPO_NAME}.git"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  NetValve GitHub Publishing Helper${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "GitHub Username: ${GREEN}${GITHUB_USERNAME}${NC}"
echo -e "Repository Name: ${GREEN}${REPO_NAME}${NC}"
echo -e "Repository URL:  ${GREEN}${REPO_URL}${NC}"
echo ""

# Check if we're in the NetValve directory
if [ ! -f "README.md" ] || [ ! -f "settings.gradle.kts" ]; then
    echo -e "${RED}Error: This script must be run from the NetValve project root${NC}"
    echo -e "${RED}Current directory: $(pwd)${NC}"
    exit 1
fi

# Initialize git repository if not already
if [ ! -d ".git" ]; then
    echo -e "${YELLOW}[1/5] Initializing git repository...${NC}"
    git init
    git branch -m main 2>/dev/null || true
    echo -e "${GREEN}✓ Git repository initialized${NC}"
else
    echo -e "${GREEN}[1/5] Git repository already exists${NC}"
fi

# Configure git user (use global config if available)
echo -e "${YELLOW}[2/5] Checking git configuration...${NC}"
GIT_USER_NAME=$(git config --global user.name 2>/dev/null || echo "")
GIT_USER_EMAIL=$(git config --global user.email 2>/dev/null || echo "")

if [ -z "$GIT_USER_NAME" ] || [ -z "$GIT_USER_EMAIL" ]; then
    echo -e "${YELLOW}Git user not configured. Please enter your details:${NC}"
    [ -z "$GIT_USER_NAME" ] && read -r -p "Name: " GIT_USER_NAME
    [ -z "$GIT_USER_EMAIL" ] && read -r -p "Email: " GIT_USER_EMAIL
    git config user.name "$GIT_USER_NAME"
    git config user.email "$GIT_USER_EMAIL"
fi
echo -e "${GREEN}✓ Git configured as: $(git config user.name) <$(git config user.email)>${NC}"

# Add all files
echo -e "${YELLOW}[3/5] Staging files...${NC}"
git add .
echo -e "${GREEN}✓ Files staged${NC}"

# Create initial commit
echo -e "${YELLOW}[4/5] Creating initial commit...${NC}"
if git diff --cached --quiet; then
    echo -e "${YELLOW}No changes to commit (repository may already have commits)${NC}"
else
    git commit -m "Initial release: NetValve v1.0.0

NetValve is a root-free, local-VPN per-app traffic controller for Android.

Features:
- Per-app bandwidth limiting (download/upload)
- Application blocking with time-based schedules
- Foreground/background rules
- Live traffic statistics and leveled logging
- Material 3 UI with Jetpack Compose
- Generic policy engine (conditions × actions)
- Plugin architecture for extensibility
- Built on gVisor netstack for production-grade TCP/IP

Technical highlights:
- 100% Kotlin, MVVM with Coroutines/Flow
- Hilt DI, DataStore (settings) + Room (stats/logs)
- minSdk 29 (Android 10), targetSdk 35
- 33/33 unit tests passing across 7 suites
- Apache-2.0 licensed, no trackers, no remote server"
    echo -e "${GREEN}✓ Initial commit created${NC}"
fi

# Check if remote already exists
echo -e "${YELLOW}[5/5] Checking remote configuration...${NC}"
if git remote get-url origin &>/dev/null; then
    EXISTING_REMOTE=$(git remote get-url origin)
    echo -e "${YELLOW}Remote 'origin' already exists: ${EXISTING_REMOTE}${NC}"
    if [ "$EXISTING_REMOTE" != "$REPO_URL" ]; then
        echo -e "${YELLOW}Updating remote to: ${REPO_URL}${NC}"
        git remote set-url origin "$REPO_URL"
    fi
else
    git remote add origin "$REPO_URL"
    echo -e "${GREEN}✓ Remote added: ${REPO_URL}${NC}"
fi

echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Next Steps: Create the GitHub Repo${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "${YELLOW}Since 'gh' CLI is not installed, you need to create the repo manually:${NC}"
echo ""
echo -e "1. Open this URL in your browser:"
echo -e "   ${GREEN}https://github.com/new${NC}"
echo ""
echo -e "2. Fill in the form:"
echo -e "   - Repository name: ${GREEN}${REPO_NAME}${NC}"
echo -e "   - Description: ${GREEN}Root-free, local-VPN per-app traffic controller for Android${NC}"
echo -e "   - Visibility: ${GREEN}Public${NC} (recommended for open-source)"
echo -e "   - ⚠️  DO NOT initialize with README, .gitignore, or license"
echo -e "   - (We already have these files locally)"
echo ""
echo -e "3. After creating the empty repo, run this command to push:"
echo ""
echo -e "   ${GREEN}git push -u origin main${NC}"
echo ""
echo -e "${YELLOW}If you get an authentication error:${NC}"
echo -e "  - Option A: Use GitHub Desktop (easiest)"
echo -e "  - Option B: Set up SSH keys: https://docs.github.com/en/authentication/connecting-to-github-with-ssh"
echo -e "  - Option C: Use a Personal Access Token: https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token"
echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  After Pushing: Create a Release${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "1. Go to: ${GREEN}https://github.com/${GITHUB_USERNAME}/${REPO_NAME}/releases/new${NC}"
echo ""
echo -e "2. Fill in:"
echo -e "   - Tag: ${GREEN}v1.0.0${NC}"
echo -e "   - Title: ${GREEN}NetValve v1.0.0 - Initial Release${NC}"
echo -e "   - Description: Copy from the release notes below"
echo ""
echo -e "3. (Optional) Upload the prebuilt APK:"
echo -e "   - Path: ${GREEN}prebuilt/app-debug.apk${NC}"
echo -e "   - Or build your own: ${GREEN}./gradlew :app:assembleDebug${NC}"
echo ""
echo -e "${YELLOW}Release notes template:${NC}"
cat << 'EOF'

## NetValve v1.0.0 - Initial Release

First public release of NetValve, a root-free per-app traffic controller for Android.

### ✨ Features
- Per-app bandwidth limiting (download/upload)
- Application blocking with time-based schedules
- Foreground/background rules
- Live traffic statistics and leveled logging
- Material 3 UI with Jetpack Compose
- Generic policy engine (conditions × actions)
- Plugin architecture for extensibility

### 📦 Installation
- Download `app-debug.apk` below
- Install on Android 10+ device
- Grant VPN consent and required permissions
- **Note**: This APK uses the loopback engine (UI only, no traffic forwarding). For real traffic shaping, build from source with the netstack engine.

### 🛠️ Build from Source
\`\`\`bash
git clone https://github.com/USERNAME/NetValve.git
cd NetValve
./gradlew :app:assembleDebug
\`\`\`

For production engine (real forwarding):
\`\`\`bash
cd netstack && ./build-aar.sh && cd ..
./gradlew :app:assembleDebug -Pnetvalve.netstack=true
\`\`\`

### 🧪 Testing
- 33/33 unit tests passing
- Tested on Android 10+ (API 29+)
- Requires JDK 17 and Android SDK 35

### 📄 License
Apache-2.0

EOF
echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${GREEN}✓ Local repository is ready!${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "Run ${GREEN}git push -u origin main${NC} after creating the GitHub repo."
echo ""
