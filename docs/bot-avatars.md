# Bot identity

The approved family has 20 looks and eight colours. Android draws the shapes from the approved construction as vectors, at the requested size, inside a circular crop. It adds no avatar picker.

Both platforms must use the raw tmux session name, not the editable display name, host UUID or provider. Hash its exact UTF-8 bytes using unsigned 32-bit FNV-1a: initial value `2166136261`, XOR each unsigned byte, multiply by `16777619`, wrapping at 32 bits. Face is `hash % 20`; colour is `floor(hash / 20) % 8`.

Face order: Classic, Happy, Square eyes, Calm, Cyclops, Wink, Curious, Dome, Peek left, Peek left square eyes, Rise from bottom, Square head, Tilted, Close-up, TV, Headphones, Profile, Corner peek, Grill mouth, Hang from top.

Colour order: White `#FFFFFF`, Sage `#95DE69`, Clay `#FF6F28`, Sand `#FFC455`, Mist `#5A9AE9`, Lilac `#9F6CFC`, Rose `#FF667B`, Mint `#5EFED1`.

Cross-platform vectors: `HT Product Lead` → face 0, colour 3; `SF Design Lead` → face 8, colour 3; `机器人` → face 9, colour 4. Renaming the displayed title does not change the robot.

Desktop implementation: https://app.hypertask.ai/detail/project-2060/1150
Design: https://www.figma.com/design/8Yzu3N21UlCL7lA3YXm45D?node-id=118-1132

GitHub's **Robot faces visual proof** rejects the original inbox with the unchanged robot journey, then captures pinned bots, 68 dp rows, previews and the 28 dp chat-title robot. **Verify real Android UI**, with `feature=robot-faces.md`, `released_robots=true` and `expected_commit`, installs the published signed APK and checks robot identity after real SSH discovery and opening its chat.
