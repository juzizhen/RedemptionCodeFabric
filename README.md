# RedemptionCodeFabric
A Minecraft Fabric mod that adds a redemption code system to your server.

## Features
- **Flexible Code Types**: Supports various code types including:
    - `ONCE`: Can be used only once globally.
    - `PERMANENT`: Can be used multiple times by different players, but each player can only use it once.
    - `PERSONAL`: Can only be used by a specific player, once.
    - `GLOBAL_LIMIT`: Can be used a limited number of times globally.
    - `TIMED`: Can only be used within a specified time frame.
    - `CYCLE`: Can be used repeatedly by the same player after a cooldown period.
- **Reward Types**:
    - `item@<item_id>{NBT}`: Gives a specified item, with optional NBT data.
    - `[hand]`: Gives the item currently held in the player's main hand (when generating the code).
    - `exp@<amount>[P|L]`: Gives experience points (`P` for points, `L` for levels).
    - `permissions@<level>`: Grants a temporary permission level (requires a compatible permission plugin).
- **Command-based Management**: Generate and redeem codes directly in-game.
- **Configurable**: Customize logging behavior and other settings.
- **Data Persistence**: Codes and usage history are saved to JSON files.

## Commands

### `/rcode generate <type> [options] <reward>`
Generates a new redemption code. Requires operator permission level 2.

**Arguments:**
- `<type>`: The type of code to generate (e.g., `once`, `permanent`, `personal`, `global_limit`, `timed`, `cycle`).
- `[options]`:
    - `code <code>`: (Optional) Specify a custom code string. If omitted, a random 16-character code will be generated.
    - `player <player>`: (Required for `personal` type) The player who can use this code.
    - `count <number>`: (Required for `global_limit` type) The total number of times this code can be used globally.
    - `start <timestamp>`: (Optional for `timed` type) The start time (Unix timestamp in milliseconds) when the code becomes active.
    - `end <timestamp>`