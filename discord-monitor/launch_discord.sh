#!/bin/bash
# Launch Discord with Chrome DevTools Protocol debugging enabled.
# Usage: ./launch_discord.sh [port]

CDP_PORT="${1:-9222}"
DISCORD_PATH="/Applications/Discord.app/Contents/MacOS/Discord"

if [ ! -f "$DISCORD_PATH" ]; then
    echo "ERROR: Discord not found at $DISCORD_PATH"
    echo "Please install Discord desktop app or update the path."
    exit 1
fi

# Kill existing Discord instances
echo "Closing existing Discord..."
pkill -f "Discord" 2>/dev/null
sleep 2

echo "Launching Discord with --remote-debugging-port=$CDP_PORT"
"$DISCORD_PATH" --remote-debugging-port="$CDP_PORT" &
DISCORD_PID=$!

echo ""
echo "Discord PID: $DISCORD_PID"
echo "CDP endpoint: http://127.0.0.1:$CDP_PORT"
echo ""
echo "Wait for Discord to fully load, then run:"
echo "  cd discord-monitor"
echo "  python -m src.main --config config.yml --dry-run"
