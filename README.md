# jrddunbr's train mod

jrddunbr's train mod runs on Minecraft Java Edition 26.3 with NeoForge 26.3.0.115 and Java 25. Install the `trainmod-<version>.jar` from [GitHub Releases](https://github.com/jrddunbr/trainmod/releases) in the `mods` folder on the client and server. WorldEdit 7.4.6 is optional.

This project uses the [MIT License](LICENSE).

## Build and drive

- Find the equipment in the **jrddunbr's train mod** Creative tab. In Survival, craft track kits, tools, vehicles, ballast, stands, and railway structures at a crafting table.
- Pick the track item you want. Right-click a support block or the open end of an existing rail to place the entire piece. Face the direction the track should extend. With curves and turnouts, aim slightly toward the side you want the branch to take. At a diagonal approach, click either side of the open end to attach the curve from that direction. For a centered click, your position decides the side. The placement preview shows the route and marks missing support blocks red. A stand preview turns green when it can control a set of points. Straight kits cover 1, 3, 6, 8, or 12 blocks and follow cardinal or diagonal approaches. Rail kits stack up to 64 placed rail blocks, counting shared track once. Older oversized freight stacks are kept intact until you split them.
- Break any rail in a piece to lift the whole piece. In Survival it returns one track item. Ties vary in length and angle by default. Set `variedRailTies = false` in `config/trainmod-client.toml` for uniform ties.
- Use **Straight Bridge Rail** for a one-block straight with deep timber ties that sit flush on the deck. It snaps to cardinal and diagonal track just like a regular straight.
- Support every segment. The ends of the three curves are 4, 8, or 12 blocks forward and 2, 3, or 5 blocks to the side. Connect diagonal rail at the corner beyond the end of a curve. Place a **1:6** or **1:8** grade from its low end. Face uphill and right-click the ground or an open rail end. The kit rises one block after 6 or 8 blocks of horizontal run and can sit on level ground. Build the next piece at the higher elevation. The parallel turnout's exits are three blocks apart in its cardinal orientation and two diagonal steps apart in its diagonal orientation. Face along a diagonal approach to lay a diagonal turnout. Place the rising medium and large curves from their low end on a cardinal or diagonal rail. They exit one block higher. Level curves join cardinal and diagonal track from either end.
- Use a vehicle item on complete track to place the vehicle facing where you are looking. Leave about three to five blocks between vehicle centers. Right-click each facing end with the **Coupler Tool** to link them. Clicking the body lets the tool select a facing free end. Couplers that are already close can link in one click. Right-click the coupler itself to release a link. Coupling pulls the cars together until their couplers meet. Automatic coupling notifies players within 16 blocks of the joined couplers. Manual coupling confirms the link privately to the player using the tool. Driven trains and rolling cars also couple when their free ends meet at a closing speed over 1 mph. A consist can have up to 24 vehicles. Coupled locomotives follow the same engineer. A moving locomotive keeps control, with priority among otherwise equal engineers going to the rider seated longest.
- Right-click the RS3, electric shunter, or bore to board. Hold **W** to drive forward and **S** to slow and reverse. Release both to brake. **Shift** dismounts. The server sends each car’s actual speed and position so the client can smooth movement and cab motion between updates. Mod trains run on vanilla rails, including corners and grades. Vanilla minecarts use vanilla track. The flatcar, gondola and boxcar are 2.5–3 blocks long and up to 1.19 blocks tall above the railhead. Leave room for vehicle ends to swing through turns.
- Right-click a freight car to open its 27-slot cargo inventory. Rail-only loads on flatcars and gondolas show up as strapped stacks. Gravel fills the gondola in proportion to the cargo. Click either boxcar door to slide it open or shut over five ticks. Sneak-right-click either end of a boxcar or gondola to board one of its two seats. Release Shift after boarding and press it again to dismount.
- Walk into an unpowered car to push it along the rails. It coasts on level track and accelerates downhill. Right-click the offset rear handbrake wheel to set or release the brake. Its center turns 90° when applied and turns back when released. A set brake holds the whole coupled train.
- At an open rail end, the train stops with the leading axle of the first car to reach the end still on the rail. The tunnel bore can continue with its front tires on the ground while its rear rail wheels remain supported.
- Aim at a car to see its click targets. Couplers are cyan, handbrakes amber, and boxcar doors green. Minecraft's entity hitbox display also shows the train body outline.
- Right-click any clear rail with a **Buffer Stop** to mount it at the clicked position. Its beam faces you along the rail at coupler height. It stops arriving trains and pauses the tunnel bore. Break the stop to remove it or lift the rail to recover it.

## Track crafting

Craft a **Rail Extruder** at a crafting table. Put a furnace between two pistons in the top row, redstone dust between two copper ingots in the middle, and three iron ingots along the bottom.

Right-click the extruder and load iron ingots. One ingot rolls into **16 individual rails** in 40 ticks (2 seconds at 20 TPS), using **400 FE** at 10 FE per tick. The extruder accepts Forge Energy from any face and buffers 4,000 FE. Hoppers can feed ingots from the top or sides and collect rails from below. For packs without power producers, set `railExtruderRequiresPower = false` in `config/trainmod-synced.toml`.

One plank of any type yields one **bundle of four ties** in the stonecutter. In the crafting grid, place two planks diagonally to make two bundles. One gravel yields **two ballast blocks** in either workstation. A single-block track kit uses **two extruded rails** and one tie bundle. Each recipe below yields one kit. Sizes in the ingredient column refer to completed Trainmod track kits.

| Track kit | Ingredients |
|---|---|
| Single-block straight | Two rails in the outer slots of the top row, a tie bundle in the center, and ballast below it |
| Single-block bridge straight | Two rails in the outer slots of the top row and a tie bundle centered below them |
| 3-block straight | Three single-block straights |
| 6-block straight | Two 3-block straights |
| 8-block straight | One 6-block straight and two single-block straights |
| 12-block straight | Two 6-block straights |
| Small curve | One 3-block straight, two single-block straights, and a tie bundle |
| Medium curve | One 8-block straight, one single-block straight, and a tie bundle |
| Large curve | One 12-block straight, one single-block straight, and a tie bundle |
| Rising medium or large curve | The matching level curve and one stone |
| 1:6 or 1:8 grade | The matching 6- or 8-block straight and two stone |
| 90° diamond crossing | Two 3-block straights directly above and below a center tie bundle |
| 45° diamond crossing | Two 3-block straights in opposite corners and a tie bundle in the center |
| 45° turnout | One 3-block straight, one single-block straight, one small curve, and a redstone torch |
| Parallel turnout | One 8-block straight, one medium curve, and a redstone torch |
| Long parallel turnout | One 12-block straight, one 3-block straight, one large curve, and a redstone torch |
| Small or medium wye | Two matching small or medium curves and a redstone torch |
| Scissors crossover | Two 12-block straights, two medium curves, and four redstone torches |

Only the single-block straights and diamond crossings require a shaped grid. Regular straight kits can be placed diagonally.

## Locomotive supplies

- Sneak-right-click the **RS3** to open its fuel inventory. It accepts buckets tagged `c:buckets/diesel`. When that tag is empty, craft a diesel bucket from one water bucket and one coal. A bucket supplies 1,200 powered ticks and leaves an empty bucket in its fuel slot. The lit headlight indicates the direction of travel.
- Couple the **PRR 4-4-2** directly to a **steam tender**. Right-click the tender body for its 64-coal slot and eight water-bucket slots. Right-click the highlighted seat on top to board. Press sneak to dismount. With the tender coupled, drive in the direction it faces. Each coal and water bucket provides 400 powered ticks. Collect empty buckets from the supply inventory.
- Hold **H** for the steam whistle or RS3 horn. Press **B** to start or stop the bell. Both keys can be rebound under Railway in the Controls menu.
- The RS3 and electric shunter run equally fast in either direction. New **electric shunters** start with an empty reserve. A working overhead span powers movement and charges the reserve even when parked. A full reserve provides 200 powered ticks away from the wire.

## Superelevation

The smallest curve uses a 20 mph design reference and eases into cant over one block at either end. The medium and large curves use 40 mph at 1:8 longitudinal scale, capped at the equivalent of six inches of prototype cant, and ease over two blocks at either end. The ends ease to level for straight, diagonal, grade, or switch track. Curves that meet with the high rail on the same side carry the bank through their joint at the lower of their two design speeds. Opposing curves level out at the join. Vehicles lean with the rail.

## Turnouts and signal cabins

Put a switch stand beside the first rail block of a turnout. On diagonal turnouts, the stand can sit two blocks away along an axis or across the corner. Click the stand to toggle power, or run a redstone circuit to the first rail block. The 45° and parallel turnouts select straight without power and diverging with power. Both wye sizes select left without power and right with power, viewed from the common approach. The medium wye matches the medium curve footprint on each branch. Put one stand beside each of the scissors crossover’s four point sets. Each controls its own straight or crossover route. Line up both ends for a through move. New 45° and 90° diamond crossings carry both routes through the intersection in five track blocks. Existing nine-block crossings retain their approaches. A train locks the switch while its running gear is over either moving point rail. A queued throw proceeds as soon as the running gear clears the blades. Stock farther down an approach stays on that approach when the points throw. The two point blades stay attached at their heels and slide together at their toes. Stock rails, frogs, and check rails stay fixed. The stand head turns with the point blades in the same quarter-second motion. When you operate the stand, it changes its redstone output at the start of the throw. The white rectangular flag means straight, and a red arrow points toward the selected branch.

Right-click the steel signal cabin to open its door. It has a one-block footprint, corrugated steel siding, and a 30° gable roof. Crouch to enter. The handle and padlock are decorative. Legacy signals remain in existing worlds as inert blocks.

## Bridges and catenary

- Lay a steel girder deck in line with its pier. Place up to six clear sections from each pier. Start from both piers to close a longer span. Stack steel girders to extend the piers, or add one regular girder to the side of a main beam for a catenary pole.
- A **Wide Steel Girder Deck** places one three-block-wide section at the same height as a regular deck. The pier I-beams sit directly below the outer girders. Full-length X braces run beneath square-section timber ties that are twice as wide and deep as the track ties and use the same texture and spacing. To craft one section, put a tie bundle in the center of the top row and a regular steel girder in each outer slot below it.
- Existing stone arches and wooden trestles load as legacy blocks.
- Place single-track catenary one block to the side of a track and face toward the rail. The double-track tower spans two tracks three blocks apart from center to center. Both hold wires three blocks above the railhead. Face diagonally during placement to rotate a pole’s arm by 45°. Along curves and diagonals, place the poles farther from the rail so their longer arms clear the loading gauge. Place gantry blocks directly over the rail in the fifth block layer of a tunnel, counting the track block as layer one. On level track this gives four blocks of wire clearance. Gantries in the fourth layer give three blocks. The wire meets the underside of each insulator, including on grades and spans between mounts at different heights.
- Connect two cantilever towers or two gantries within 16 blocks along a continuous route. The wire follows grades as well as curves. Wires span both branches through turnouts when each branch connects to an appropriate tower. Breaking the rail route or removing a tower cuts power to its span.

## Tunnel bore and ballast

- Sneak-right-click the bore or right-click with a hopper to open its supplies. In Survival, the first three slots take vanilla rails or any length of regular straight kit, gravel or railway ballast, and cobblestone. The bore converts longer kits into single-block sections as needed. The take-only reserve slot holds the unused sections after saving and loading. Creative drivers can build with empty supply slots.
- Right-click with a chest for the first 54 cargo slots, or sneak-right-click with a chest for the other 54. Place the 4.15-block-long bore on a level straight or diagonal facing the cut. The clicked rail goes beneath the rear truck. The front tires lower automatically when the front rail wheels reach the end of the rails, and retract when rail is available under them. The rear truck needs rail, and both front tires need solid ground within reach when deployed. Couple freight cars behind the bore. The bore keeps its own chunk, the chunk ahead, and the chunks occupied by the trailing consist loaded while parked.
- Board, press **Space** to toggle work, and drive forward. The nose clears three-wide, four-high slices, and the bore feeds one section of track at a time beneath the middle of the machine. It lays the supplied gravel or ballast below the rail and cobblestone below that. Existing gravel and ballast serve as roadbed without using a supply item. The bore clears water and lava from the cut. Vanilla fluid can flow back in from outside the cut.
- Excavated items fill the trailing freight cars in order, then the onboard cargo buffer. The bore pauses and displays the specific missing supply or obstruction, including its location for blocked cuts.
- Ballast blocks have a full, flat top with one-block shoulders that slope at 45°. Track raises the crest and its shoulders reach the same toe. The bed follows the track centerline around curves, meets diagonals corner to corner, and tapers at the track end into bare ballast or under the first bridge rail. The taper at a bridge starts from the full raised profile when there is a ballast block under the approach track. The steel profiles have end faces at open track ends. Raised ballast blends into neighboring ballast up to three blocks away at the same elevation, including on turnouts. Walk up its gravel shoulders one shallow step at a time. Right-click the ballast regulator to load gravel or ballast and pick **Narrow** or **Wide**. Narrow starts with one block directly below the rail. Wide starts with three. Both widen by one block per side at each of the four lower layers, giving a 45° slope. Both modes spend one item per block, replace eligible sand, gravel, stone, cobblestone, or dirt, and fill supported gaps. Sneak-right-click the car to toggle regulation, then drive it as part of a consist.

## WorldEdit

Select the entire piece, including its support blocks, when copying track. Paste directly or rotate by multiples of 90° and mirror horizontally before pasting. Save and load layouts as Sponge schematics. For example, use `//set trainmod:straight_rail[rotation=0,type=left,segment=0]` for a north–south straight or `//set trainmod:straight_rail[rotation=14,type=left,segment=0]` for a northwest–southeast diagonal. For multiblock pieces, place a complete piece with its track item first, then copy it to preserve the segment indexes. An incomplete piece behaves as a break in the railway.

## Development

`./gradlew build` runs the geometry tests and builds the JAR in `build/libs/`. Use `./gradlew runClient` for a development client with PulseAudio and the offline username `jrddunbr`. Use `./gradlew runClient -PclientAuth` to sign in with DevLogin and load your Minecraft account skin.

Use `./gradlew runGameTestServer` for the in-world tests. Place the NeoForge WorldEdit 7.4.6 JAR in `run/game-test/mods/` to include the WorldEdit compatibility test.

A clean checkout at a version tag such as `v0.2.18` builds `trainmod-0.2.18.jar` and reports `0.2.18` in NeoForge. Development builds append the number of commits since the nearest reachable version tag, the branch, and the short commit hash, as in `0.2.17-dev.1.feature-build.g675da8b79061`. Uncommitted changes add `.dirty`.

To publish a release, create and push a `vMAJOR.MINOR.PATCH` tag on the commit you want to release. GitHub Actions builds and tests that commit with Java 25, then publishes the JAR and a changelog based on commits since the previous reachable version tag.
