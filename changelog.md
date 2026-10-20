## 2.2.0

Requires MenuKit 7.3.0 or newer, below 8.0.0. Your sandboxes and settings carry over.

### Changed

- The Sandboxes button is now a small square button in the pause menu's row of small buttons, with new icons for it and the quick sandbox button.
- Clicking ✎ puts the cursor in the name box right away.
- Cards update in place when a sandbox is made, renamed, synced, or deleted, instead of the whole screen being rebuilt.

### Fixed

- Pressing Escape while renaming a sandbox closed the Sandboxes screen. It now cancels the rename.
- The Sync and Delete confirmations now name the sandbox.

## 2.1.0

Requires MenuKit 7.2.0 or newer, below 8.0.0. Keybindery 1.3.0 or newer is optional, for overlay keys. Your sandboxes and settings carry over.

### Added

- An overlay that shows where a sandbox and your world differ, turned on with the eye on a sandbox's card. Blocks the other world has and yours doesn't show as see-through ghosts in their real colors, outlined green. Blocks only your world has are outlined red, and blocks that differ are outlined yellow. Looking at a difference shows what the block is in each world at the top of the screen. Inside a sandbox, the overlay compares it with your world.
- An overlay key for each sandbox, set with the key button on its card. It needs Keybindery 1.3.0 or newer, and the card says when another binding uses the same key.
- A Duplicate button, which copies a sandbox as it is now, with its own player and inventory, into a new card.
- A **Hop to sandbox and back** key in Controls, unbound by default. It does the same thing as the quick sandbox button.
- The quick sandbox button can also show in your inventory. It's off by default and turned on in Settings.

### Changed

- The Sandboxes screen is now a list of cards, one per sandbox, each with its own buttons. Sandboxes are renamed on their card, and Settings is a tab on the same screen.
- Mod Menu opens the Sandboxes screen from inside a sandbox too, and the card for the sandbox you're in is marked.
- The quick sandbox button's tooltip names where it will take you.
- Making, syncing, or deleting a sandbox no longer locks the whole screen. The card shows its progress, and the rest of the screen stays usable.

### Fixed

- Leaving the world while a sandbox was being copied, synced, or deleted could leave a broken copy. Save and Quit, the Return and quick buttons, Join, and the hop key now wait until the copy is done.
