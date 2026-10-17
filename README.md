# Sandboxes

Sandboxes gives you a creative copy of your world to try things in. You can test a build, a redstone machine, or a new shape for a hillside right where it's meant to go, then go back to your survival world exactly as you left it. Nothing you do in a sandbox reaches your real world.

I built it so you can test an idea in its real surroundings instead of in a flat test world. Once you've made changes, an overlay shows you where the sandbox and your world differ.

It's client-side, and it works with your singleplayer worlds.

## Features

### Sandboxes of your world

- Make a sandbox from the pause menu. It's a full copy of the world you're in, and each world keeps its own list of sandboxes.
- Join a sandbox and you're in creative mode with commands on. Red text at the top of the screen names the world it came from, so you always know which one you're in.
- Return to your world and you're back in survival with your own inventory, where you left off. Nothing crosses between the two worlds unless you sync.

### Managing sandboxes

- Every sandbox gets a card with its world picture, name, the date you last joined it, and its size on disk.
- Rename a sandbox to keep track of what it's for. A name you've set stays through a sync.
- Sync a sandbox to replace it with a fresh copy of your world when your world has moved on.
- Duplicate a sandbox to keep it as it is now, player and inventory included, before you try something else in it.
- Delete a sandbox when you're done with it.

### Overlay

- Shows where a sandbox differs from the world you're in. A block the sandbox has and your world doesn't shows as a see-through ghost outlined green. Blocks only your world has are outlined red, and blocks that are different are outlined yellow.
- Look at a difference and a line at the top of the screen says what the block is in each world.
- From inside a sandbox, the overlay compares it with your real world.
- With [Keybindery](https://modrinth.com/mod/keybindery) installed, each sandbox can have its own key to turn its overlay on and off.

### Quick hop

- Once a world has a sandbox, a small button in the corner of the pause menu takes you to the sandbox you used last, and back again from inside it.
- The same button can also show in your inventory, and there's a key for it in Controls.

## Requirements

- [MenuKit](https://modrinth.com/mod/menukit) 7.2.0 or newer, below 8.0.0. Sandboxes uses it for its screen, its buttons, and the text at the top of the screen.
- [Fabric API](https://modrinth.com/mod/fabric-api).

## Pairs well with

- [Keybindery](https://modrinth.com/mod/keybindery) 1.3.0 or newer, for a key per sandbox that turns its overlay on and off.
- [Mod Menu](https://modrinth.com/mod/modmenu), which opens the Sandboxes settings from the mods list.

## Compatibility

Minecraft 26.3 on Fabric, client only. Sandboxes copies your world's save folder, so it only works with local singleplayer worlds, not servers or LAN games.

## How to use

### The pause menu

- In a singleplayer world, the pause menu has a **Sandboxes** button directly above **Save and Quit to Title**. Save and Quit and anything below it move down one row to make room.
- Click **Sandboxes** to open the Sandboxes screen for the world you're in.

### The Sandboxes screen

- The screen has two tabs, **Sandboxes** and **Settings**. **Done** at the bottom goes back to the pause menu.
- With no sandboxes yet, the Sandboxes tab shows one line explaining what a sandbox is.
- Click **+ New sandbox** to make one. The world is saved first, then copied into a new sandbox named with the current date and time (year-month-day, then hours and minutes). "Creating sandbox..." shows where the new card will appear, and the button is grayed out until the copy is done.
- Each sandbox has a card. It shows the world picture, the sandbox's name, a gray line with the date you last joined it (or "never joined") and its size on disk, and a row of buttons.
- Cards are ordered by when you last joined them, most recent first.

### Card buttons

From left to right:

| Button | What it does |
| --- | --- |
| Eye | Turns this sandbox's overlay on or off. See The overlay below. |
| Key button | Sets this sandbox's overlay key. It only shows when Keybindery 1.3.0 or newer is installed. |
| **Join** | Saves and closes your world, then opens the sandbox. |
| **Sync** | Replaces the sandbox with a fresh copy of your world, after a confirmation. |
| ✎ | Renames the sandbox. |
| ❐ | Duplicates the sandbox. |
| ✕ | Deletes the sandbox, after a confirmation. |

- **Join** opens the sandbox in creative mode with commands on. The first time, you stand where you were when the sandbox was made. After that, you return to where you last were inside that sandbox. A sandbox of a hardcore world isn't hardcore, and its difficulty is set to Hard.
- **Sync** asks "Sync this sandbox?" first, since anything built in the sandbox is lost. Your world is saved, and the sandbox is replaced with a fresh copy of it. A name you've set stays. A name that is still the generated date and time is updated to the current date and time. The sandbox keeps its overlay key.
- **✎** turns the name into a text box. Press Enter or click **✓** to save it. Names can be up to 64 characters, and an empty name keeps the old one.
- **❐** copies the sandbox as it is now, including its player and inventory, into a new card. A name you've set gets " (copy)" added to it, and a generated name gets the current date and time. The overlay key stays with the original.
- **✕** asks "Delete this sandbox?" first. A deleted sandbox can't be brought back.
- While a sandbox is being synced or deleted, its card shows "Syncing sandbox..." or "Deleting sandbox..." in place of its buttons. The rest of the screen stays usable.

### Inside a sandbox

- Red text at the top of the screen reads "Sandbox of world" followed by the world's name.
- The pause menu shows **Return to** followed by the world's name, in place of **Sandboxes**. Click it to save the sandbox and open your world. You're back in survival with your own inventory, where you left off.
- **Save and Quit to Title** works as usual. Sandboxes don't appear in Minecraft's world list, so to get back into one, open its world and join it from the Sandboxes screen.
- The Sandboxes screen opens from inside a sandbox through Mod Menu. The card for the sandbox you're in says "You're in this one" and has no **Join**, **Sync**, or ✕ button.

### While a copy is running

- Making, syncing, duplicating, or deleting a sandbox runs in the background.
- Until it finishes, nothing leaves the world. **Save and Quit to Title**, **Sandboxes**, **Return to**, **Join**, and the quick sandbox button are grayed out, and the hop key does nothing.
- Closing the game itself isn't blocked, so let the copy finish first.

### The overlay

- Click the eye on a card to turn on that sandbox's overlay. It appears when you close the screen. Click the eye again to turn it off.
- Only one overlay shows at a time. Turning on another sandbox's overlay turns off the one that was showing.
- In your world, the overlay compares your world with that sandbox. Inside a sandbox, the eye on its own card compares the sandbox with your world.
- Each difference is drawn from the point of view of the world you're standing in:

| Outline | Meaning | How it's drawn |
| --- | --- | --- |
| Green | The other world has a block where yours has air. | A see-through ghost of the other world's block, in its real colors. |
| Red | Your world has a block where the other world has air. | Your block as it is, outlined. |
| Yellow | Both worlds have a block there, but different ones. | Your block as it is, outlined. Break it and the ghost of the other world's block appears. |

- Water, lava, chests, and other blocks without a regular model get a full-block outline and no ghost.
- The overlay compares blocks, not their state, so a turned stair or a grown crop doesn't count as a difference.
- It covers the chunks loaded around you and updates as you place and break blocks.
- A line at the top of the screen reads "Overlay:" followed by the world it compares with.
- Look at a difference up to 48 blocks away and a second line says what the block is here, then in the other world, such as "Stone → Oak Planks". A solid block that's the same in both worlds stops the line from reading anything behind it.
- The overlay turns off when you leave the world, and when the sandbox it shows is synced or deleted. Changing dimension keeps it on and compares the new dimension.

### Overlay keys

- With Keybindery 1.3.0 or newer installed, each card has a key button. Click it and press a key to set that sandbox's overlay key. Right-click it to clear the key.
- In your world, a sandbox's key turns that sandbox's overlay on or off. Inside a sandbox, its own key compares it with your world.
- Overlay keys belong to their world, so they don't appear in Controls and only work in that world and its sandboxes.
- If another binding uses the same key, the card adds a line reading "Overlay key also used by:" followed by that binding. Both still work.
- Without Keybindery, the key button doesn't show and the eye works as usual.

### Quick sandbox button and hop key

- Once a world has at least one sandbox, a small button appears in the top-right corner of the pause menu.
- In your world, it joins the sandbox you joined most recently. Inside a sandbox, it takes you back to your world. Its tooltip says where it will go, as "Jump to" followed by the sandbox's name or "Back to" followed by the world's name.
- The same button can show in the top-right corner of your inventory, in survival and creative. It's off until you turn it on in Settings.
- The **Hop to sandbox and back** key, under Sandboxes in Controls, does the same thing as the quick button. It starts unbound. In a world with no sandboxes it does nothing.

### Settings

The Settings tab of the Sandboxes screen holds two switches. Mod Menu's config button opens the same screen on this tab. Opened from the title screen, only the Settings tab shows.

| Setting | Default | What it does |
| --- | --- | --- |
| Quick sandbox button | On | Shows the quick sandbox button in the pause menu's top-right corner. |
| Quick sandbox button in inventory | Off | Shows the same button in the top-right corner of your inventory screen. |

### Upgrading from Sandboxes 1.0

- Sandboxes made with the first version move into the new layout automatically, with their names intact, the first time you launch Minecraft with this version.
- A sandbox folder that can't be moved is left where it is and tried again at the next launch. Nothing is deleted.

## License

MIT.
