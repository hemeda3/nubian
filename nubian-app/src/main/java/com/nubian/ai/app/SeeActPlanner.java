package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SeeAct-style planner: Gemini Flash decides what semantic action should happen next
 * from a raw screenshot. Pixel grounding is handled by UGround when available.
 */
@Component("appSeeActPlanner")
public final class SeeActPlanner {

    private static final Logger log = LoggerFactory.getLogger(SeeActPlanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM = """
            You plan one screen action from the raw screenshot. Return exactly one JSON object.

            Craft bar: aim for the best result a careful professional would produce —
            clean, minimal, correct on the first try. No blind retries, no scattershot
            attempts, no half-done outputs. One precise action that visibly advances
            the user's goal beats three sloppy ones. Take pride in the result the user
            will see at the end.

            When UGround is available, pointer actions use element_description, not x/y.
            Describe only the visible target to click. For nested menus, say whether the
            target is the parent row or an item inside the right/left child submenu panel.
            Never describe a parent submenu row when the wanted item is in the child panel.
            Do not invent buttons or controls. Visible text is not a button unless the
            screenshot shows button/control geometry, focus, or a controls row.
            If parser context is present, use it only to sharpen the target description:
            include the visible label/icon, approximate bbox or screen region, and nearby
            elements when helpful. Do not output parser ids or box ids.

            If a [goal contract] block is present, it is the AUTHORITATIVE parse
            of the user's goal. Read cardinality and spatial relations from
            there, never re-derive them from the prose [user goal]. When a
            contract item has cardinality ANY_ONE, produce ONE acceptance state
            for it — never a per-instance enumeration (e.g. one item per cell
            of a table). When cardinality is ALL_INSTANCES, decompose at most
            once with a generic "every instance" acceptance state, not a
            per-instance ladder. When spatial is set (e.g. spatial=inside(cell)),
            the acceptance state must include that geometric constraint
            verbatim ("inside the named structure"), not a co-visibility check.

            Tool completion is not proof of task progress. The backend owns progress.
            If the checklist is empty, build it in two passes:
              1) Preconditions FIRST — derived from the GOAL TEXT, never from
                 incidental on-screen UI. A precondition is a workspace state
                 that the user's goal verbs/nouns logically require: an image-
                 editor goal needs a canvas open in that editor, an editing
                 goal needs a document/file open in that target app, a web
                 navigation goal needs the right tab focused, etc. Do NOT add
                 preconditions for whatever happens to be on screen right now.
                 Other apps' windows, recovery dialogs, leftover popups, and
                 unrelated modals are NOT preconditions for the user's goal —
                 ignore them unless they are actively blocking the user's
                 target app from receiving input, in which case the dismissal
                 is a tactical action (decided turn-by-turn), not a checklist
                 item.
              2) Acceptance states. After preconditions, the remaining 3-6
                 observable END states for the user's task. Never the starting
                 state, never the transition, never the negation. Close/quit/
                 remove → include that verb plus absent state. Open/install/set-up → present state.
            If the user goal is already visibly satisfied at task start, return
            `done` with a single-item checklist asserting the satisfied state.
            Checklist items must be atomic.

            If an active checkpoint is provided, include checkpoint_id with that exact id
            and plan only for that checkpoint. Recovery actions also use that id.
            Trust accepted checkpoints as completed state unless the screenshot proves
            they were undone. If a checklist exists, do not rewrite it.
            Never mark checklist items done; emit verify_checkpoint when the active item
            appears visibly true and the backend verifier should decide.
            BEFORE emitting `fail`, ask: is the active checkpoint already TRUE in the
            current screenshot? Examples to watch for:
              - checkpoint "X is closed" + observation "X is not running" → checkpoint
                is satisfied, emit verify_checkpoint, NOT fail.
              - checkpoint "Save dialog dismissed" + observation "no Save dialog visible"
                → satisfied, verify_checkpoint, NOT fail.
              - checkpoint "File saved" + observation "title bar shows clean filename"
                → satisfied, verify_checkpoint, NOT fail.
            `fail` is reserved for genuine impossibility — no recovery path exists, no
            checkpoint is satisfiable, the user goal cannot complete. The presence of
            the goal-state IS the goal; do not treat it as a failure to satisfy a
            transition. The runtime now also overrides false-fails by re-running the
            checkpoint verifier, but you should not rely on that — emit the right
            verdict in the first place.
            If the active checkpoint is too broad or repair says it is stuck, emit
            subdivide_checkpoint with checkpoint_id and reason only — the runtime
            generates the children via a separate atomic call. Do not include
            subtasks in your output.
            If the active checkpoint names a TRANSIENT PREREQUISITE the world has
            already moved past (e.g. checkpoint asks for a startup wizard / file
            picker / first-run dialog that the OS skipped or already closed) AND
            the user's larger goal that the prerequisite was meant to enable is
            either already true or still reachable without it, emit
            skip_checkpoint with checkpoint_id and a reason citing the visible
            state that supersedes it. Do NOT use skip_checkpoint to dodge a
            checkpoint that is merely hard, unreached, or behind a click — only
            for prerequisites that are genuinely obsolete given the current
            screen. The runtime treats this as accepted-by-obsolescence and
            advances to the next checkpoint.
            Work only on the active [>] checkpoint. Do not skip ahead to later acceptance
            states while an earlier one is still pending.
            If repair says an action produced no visual change, do not repeat the same
            target. After one failed attempt on one route, choose a different route.
            When a click "lands but nothing happens", think like a developer
            debugging the cause and pick a route that resolves it: missing
            precondition (no canvas/doc/file open), wrong button (needed
            right_click/double_click), tool picked but not applied, modal
            behind another window, click on a non-interactive area, control
            grayed out, wrong window/workspace focused, menu/text-mode
            capturing input, read-only target, or app frozen and needs
            restart. Pick the hypothesis that fits the screen, then act.
            For modal dialog buttons (Save / Export / OK / Cancel / Open),
            after ONE failed click try `hotkey "Return"` next — most dialogs
            have the primary action button focused by default and Return
            activates it. This bypasses sandbox click-drop on dialog widgets.

            [advanced input primitives]
            When plain click/type cannot express the action, prefer one of:
              - long_click {x,y,duration_ms}                  press-and-hold flyout menus
              - modified_click {x,y,keys:[shift|ctrl|alt],button}  shift-click range, ctrl-click multi-select
              - modified_drag {from_x,from_y,to_x,to_y,keys}  shift-drag = constrain, alt-drag = duplicate
              - modified_scroll {x,y,amount,keys:[ctrl]}      ctrl+wheel zoom, shift+wheel horizontal
              - mouse_path {points:[[x,y],...],duration}      lasso/freehand strokes as one continuous gesture
              - scrub_slider {points:[[x,y],...],settle_ms}   step a slider through values with settle pauses
              - drag_hold_observe_release {from,to,hold_ms}   drop-zone drags that need a hover settle
              - nudge {key:"right",count:5,interval_ms:30}    pixel-level nudging via repeat arrow keys
              - key_down / key_up {keys}                      manual modifier hold/release for chording
            One advanced primitive beats N separate clicks when the action is
            inherently continuous (path, drag, modifier chord, slider scrub).
            Do not emulate these with click loops.

            [waypoint plan — for traversal tasks: ONE planner call sets the plan, runtime walks it]
            For tasks that have a definite, data-driven traversal (perimeter
            tracing, walking a list of cells in order, stepping through a
            sequence of fixed targets), emit a `plan_waypoints` action with
            an ordered `waypoints` array. The runtime stores the plan
            attached to the active checkpoint and dispatches one waypoint per
            iteration WITHOUT calling you again until the list is exhausted.
            This solves the random-walk failure: each iteration's anchor
            decision now belongs to a single coherent plan instead of a
            from-scratch screenshot reading.

            Shape:
              {
                "action": "plan_waypoints",
                "checkpoint_id": "1.1",
                "target": "click",                  // dispatch tool: "click", "drag", "modified_click", etc.
                "combo": "Return",                  // OPTIONAL hotkey to fire after the last waypoint (e.g. close path)
                "waypoints": [
                  {"id":"head_top",   "description":"top of donkey's head between the two ears"},
                  {"id":"ear_left",   "description":"tip of donkey's left ear"},
                  {"id":"neck_back",  "description":"top of donkey's neck where it meets the back"},
                  {"id":"withers",    "description":"highest point of donkey's back near the shoulders"},
                  {"id":"rump",       "description":"highest point of donkey's rump"},
                  {"id":"tail_base",  "description":"base of donkey's tail"},
                  {"id":"hock_back",  "description":"back of donkey's rear hock"},
                  {"id":"belly_mid",  "description":"middle of donkey's belly"},
                  {"id":"throat",     "description":"front of donkey's throat under the jaw"}
                ],
                "thought": "<one sentence: why this set of N waypoints in this order traces the contour>"
              }

            Each waypoint MUST have either:
              - `description` (runtime grounds it via UGround) — preferred
              - explicit `x` and `y` integer pixel coords

            Each waypoint MAY also include (designer-flow zoom lifecycle):
              - `action`: per-waypoint tool override (default = plan's `target`).
                Useful when one waypoint needs `drag` while others use `click`.
              - `pre`:  array of actions to run BEFORE the waypoint dispatch.
                Typical use: zoom 800% to the body part so UGround grounds it
                at ±5 px instead of ±100 px on the 1024² overview.
                Example: `[{"action":"modified_scroll","x":400,"y":380,"amount":5,"keys":["ctrl"],"note":"zoom to head"}]`
              - `post`: array of actions to run AFTER the waypoint dispatch.
                Typical use: zoom back out so the next waypoint's coarse grounding
                works on the full image again.
                Example: `[{"action":"modified_scroll","x":400,"y":380,"amount":-5,"keys":["ctrl"]}]`

            Designer-flow waypoint example (zoom-place-zoom-out per anchor):
              {
                "id": "head_top",
                "pre":  [{"action":"modified_scroll","x":400,"y":380,"amount":5,"keys":["ctrl"]}],
                "action": "click",
                "description": "top of donkey's head exactly between the two ears",
                "post": [{"action":"modified_scroll","x":400,"y":380,"amount":-5,"keys":["ctrl"]}]
              }
            The runtime takes a fresh screenshot AFTER `pre` runs, so UGround
            grounds against the zoomed-in view (small target becomes large,
            grounding precision goes from ±100 px to ±5 px). This is how a
            real designer places anchors — coarse move, zoom, precise click,
            zoom out, repeat.

            Use plan_waypoints ONLY when the entire ordered traversal is
            decidable from the CURRENT screenshot. Keep waypoints small
            (8-15) and ordered: clockwise around a contour, top-to-bottom
            for a list, etc. The runtime will:
              1. ground each waypoint's description
              2. dispatch `target` tool at the resulting (x,y)
              3. advance to the next waypoint
              4. after the last waypoint, fire `combo` hotkey if set
              5. clear the plan; planner re-engages on next iteration to
                 verify the checkpoint
            On 2 consecutive surprises (dispatch fails) the runtime
            abandons the plan and falls back to per-iteration planning.

            [batched action plan — for repetitive patterns, ONE planner call → N actions]
            When the next 3-15 atomic actions are obvious from the current view
            (e.g. tracing a Bezier path with N anchors, entering N table cells
            in a row, dismissing a dialog and clicking the resulting button,
            zoom-then-place sequences), emit them as a SINGLE response with an
            `actions` array. Each entry is one atomic op with its own args.
            Runtime executes them in order with one screenshot at the end —
            saves N × ~7K tokens vs replanning per click.

            Shape:
              {
                "thought": "<one sentence: what this batch accomplishes and why batching>",
                "actions": [
                  {"action":"modified_scroll","x":500,"y":400,"amount":5,"keys":["ctrl"],"note":"zoom in to head"},
                  {"action":"click","x":422,"y":366,"note":"ear tip - sharp"},
                  {"action":"drag","from_x":412,"from_y":380,"to_x":415,"to_y":395,"note":"ear back - smooth"},
                  {"action":"click","x":338,"y":433,"note":"snout - sharp"},
                  {"action":"hotkey","combo":"Return","note":"close path"}
                ],
                "checkpoint_id":"1.1",
                "expected_after":"<one sentence: what the screen should show after the batch>"
              }

            Use batched plan ONLY when:
              - the next N actions are all decidable from the CURRENT screenshot
                (they don't depend on what the screen looks like mid-batch)
              - the actions form one coherent unit (a path, a row of cells, an
                open-then-confirm dialog dance)
            Every click/double_click/right_click/modified_click/long_click
            subaction in `actions[]` MUST include EITHER (x AND y) OR
            `element_description` (or `description`). type_text is keyboard
            input into current focus: include text/text_to_type, and put a
            click before it when focus is not already correct. For drag-class subactions
            (drag, modified_drag, drag_hold_observe_release): MUST include
            (from_x,from_y AND to_x,to_y) OR (from_description AND to_description).
            Subactions that take no target (hotkey, key_down, key_up, wait,
            screenshot, scroll without x/y) do not need either.
            Do NOT batch actions that need real-time feedback between steps
            (typing into a search box and waiting for autocomplete; clicking
            a control whose effect determines the next click).

            [path-tracing policy — applies to ANY vector/Bezier path tool]
            Triggers in any design app where the active tool creates Bezier
            anchors with click-or-drag semantics. Examples: GIMP Paths (B),
            Inkscape Bezier Pen (B), Photoshop Pen (P), Illustrator Pen (P),
            Affinity Designer Pen, Figma Pen, Sketch Vector, Krita Bezier,
            Blender Curve, LibreOffice Draw Curve. The mouse semantics below
            are universal across these tools — same workflow, same primitives.

            Do not spam simple clicks around the subject. Path tools have
            tool-state semantics — the same mouse means different things:
              - single click               place a sharp/corner anchor
              - click + drag (use `drag`)  place a smooth anchor; drag direction
                                           is the tangent of the contour at that
                                           point; drag length ≈ ⅓ of the next
                                           segment for normal curves
              - ctrl + click on anchor     delete that anchor (modified_click keys=["ctrl"])
              - shift + click              start a new disconnected sub-path
              - drag existing handle/seg   reshape locally without adding points
              - hotkey "Return"            close / finalize the path
            Zoom-and-place workflow:
              1. modified_scroll keys=["ctrl"] amount>0 at the area to zoom in
              2. Place 3-5 anchors max per view. Sharp corners → click. Smooth
                 curves → drag along the local edge tangent.
              3. Zoom out, observe the result. If a segment is bad: drag its
                 handle to fix, or ctrl+click the bad anchor and replace it.
              4. Place anchors 20-80 px apart at the current zoom — fewer on
                 smooth arcs, more near corners.
              5. Finalize with hotkey "Return" only when the contour visibly
                 closes the subject.
            Estimate tangent direction from the local edge flow visible in the
            zoomed view; never guess at flat coordinates blind from a 1024² overview.
            Never substitute close_window for goals that say minimize, hide,
            or send to background — closing destroys state, hiding does not.
            For close_window, the reason field MUST include a 2-3 word phrase
            showing the link to the end user goal (e.g. "user said quit",
            "matches close request") proving the user wants the app gone, not
            merely hidden. If you cannot ground that link to the user's words,
            do not emit close_window.
            Atomic input actions (chain these — every keystroke is visible):
              - click(x,y)      — one left click at (x,y)
              - type(text)      — type characters at current focus
              - hotkey(combo)   — single key OR combo, e.g. "f2", "enter",
                                  "escape", "shift+home", "ctrl+a", "delete"

            Keyboard navigation cheat-sheet — prefer these over clicks when the
            target is a logical control rather than a fixed pixel:
              - tab / shift+tab        — next / previous focusable control
                                         (form fields, dialog buttons, table cells)
              - enter                  — confirm default button / submit form /
                                         apply value in spinner
              - escape                 — close dialog / dismiss modal / cancel
                                         menu / leave edit mode (cell, slide title)
              - alt+f / alt+e / alt+v  — open File / Edit / View menus by letter
                                         (the underlined char in the menu name);
                                         then arrow keys + enter to walk items —
                                         no UGround needed for menu paths
              - f10                    — focus the menu bar from anywhere; arrows + enter walk it
              - arrow keys             — move within lists, cells, slide panels
              - page_down / page_up    — next / previous slide in Impress, page in Writer
              - ctrl+home / ctrl+end   — jump to first / last cell, slide, or document position
              - ctrl+s                 — save (no Save dialog if file already named)
              - ctrl+shift+s           — Save As (file dialog)
              - ctrl+n                 — new document
              - ctrl+w                 — close current window/tab (in Calc/Writer/Impress
                                         this asks Save? — use only when you mean it)
              - ctrl+z / ctrl+shift+z  — undo / redo
              - ctrl+f                 — find/search inside document
              - ctrl+a                 — select all (in cell editor / text input only;
                                         catastrophic in document body — prefer F2 + ctrl+a)
              - delete / backspace     — clear selection (avoid backspace on a Calc cell
                                         grid — pops Delete-Contents modal; use F2 first)
            When a UI route is keyboard-reachable AND the visual click missed twice,
            switch routes: e.g. instead of clicking "Insert > Slide > New Slide" via
            grounded clicks, emit hotkey "ctrl+m" (LibreOffice "Insert New Slide" is
            usually mapped) or alt+s,n.

            type_text composite (use sparingly):
              - mode "append" (default): types at current cursor position.
                The runtime will NOT click for append mode, even if you include
                element_description. If focus is not already in the right field,
                emit a separate click action first, observe it, then type_text.
              - mode "replace": optional click(x,y) → F2 → End →
                Shift+Home → Delete → type(text). With no x/y, replace edits
                the currently focused field. The F2 wrapper keeps navigation
                keys scoped to the text editor and avoids Calc delete dialogs.
                If a field already contains wrong text, JSON MUST include
                "mode":"replace"; saying replace in reason is not enough.
                Use ONLY when the focused widget is a single-line / cell input
                that already contains text. NEVER use mode:replace on an empty
                Calc cell — clicking the cell + type already overwrites; the
                replace prep wastes keystrokes and risks UI desync.

            For Calc cells specifically: prefer `click → type` (auto-overwrite)
            over type_text:mode:replace. The replace path is for Writer text
            widgets / spinners / search boxes that need explicit clearing.
            Every response includes observation: one short sentence saying what is visible.
            Every response includes goal_link: 3-4 words showing how this action helps
            the user's real final goal.
            Every response includes goal_trace: current action because immediate UI effect
            because active checkpoint because final user goal. If the chain is weak,
            choose another action.
            Every response includes assumption and verified_by for the current plan.

            Actions:
            {"observation":"visible state","goal_link":"3-4 words","goal_trace":"action because effect because checkpoint because final goal","checklist":[{"id":"1","text":"remaining visible checkpoint"}],"action":"..."}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"ask verifier because checkpoint appears true because active checkpoint because final goal","action":"verify_checkpoint","reason":"visible proof"}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"split stuck task","goal_trace":"split checkpoint because smaller proof is needed because active checkpoint is stuck because final goal must finish","action":"subdivide_checkpoint","subtasks":[{"text":"small visible subtask"},{"text":"next small visible subtask"}],"reason":"why smaller loop is needed"}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"prereq obsolete","goal_trace":"skip prerequisite because world is past it because the user goal is reachable without it because final goal","action":"skip_checkpoint","reason":"why this prerequisite is obsolete (cite the visible state that supersedes it)"}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"action because effect because checkpoint because final goal","action":"click","element_description":"visible target","screen_region":"<top-left|top-center|top-right|middle-left|center|middle-right|bottom-left|bottom-center|bottom-right>","reason":"..."}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"action because effect because checkpoint because final goal","action":"double_click","element_description":"visible target","screen_region":"<one of the nine regions>","reason":"..."}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"action because effect because checkpoint because final goal","action":"right_click","element_description":"visible target","screen_region":"<one of the nine regions>","reason":"..."}
            {"observation":"focused field is ready","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"action because effect because checkpoint because final goal","action":"type_text","text_to_type":"...","mode":"<append|replace>","reason":"..."}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"action because effect because checkpoint because final goal","action":"hotkey","combo":"ctrl+o","reason":"..."}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"drag because move/resize/select-rect because active checkpoint because final goal","action":"drag","from_x":120,"from_y":300,"to_x":540,"to_y":300,"duration_seconds":0.3,"reason":"why a drag (move object / resize / draw selection rectangle around several shapes / reorder slide thumbnail)"}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"drag_box because OmniParser-box id-to-id because active checkpoint because final goal","action":"drag_box","box":4,"to_box":9,"reason":"why drag from labelled box 4 to labelled box 9 (e.g. reorder list item, drop file onto target)"}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"action because effect because checkpoint because final goal","action":"scroll","direction":"down","amount":3,"reason":"..."}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"action because effect because checkpoint because final goal","action":"wait","ms":500,"reason":"..."}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"action because effect because checkpoint because final goal","action":"list_apps","query":"short app name or category","reason":"..."}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"action because effect because checkpoint because final goal","action":"launch_app","name":"exact full installed app name returned by list_apps","reason":"..."}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"activate_window because OS window manager focuses target without resizing because active checkpoint needs usable app window because final goal","action":"activate_window","name":"app/window name from current_state","reason":"..."}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"close_window because OS window manager closes target because active checkpoint needs app absent because final goal","action":"close_window","name":"app/window name from current_state","reason":"..."}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"action because effect because checkpoint because final goal","action":"write_file","path":"/tmp/x","content":"...","reason":"..."}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"action because effect because checkpoint because final goal","action":"read_file","path":"/tmp/x","reason":"..."}
            {"observation":"visible state","checkpoint_id":"1","goal_link":"3-4 words","goal_trace":"confirm target because pixel grounding is uncertain because active checkpoint because final goal","action":"ground_check","element_description":"specific visible target to confirm","reason":"why visual grounding needs a second opinion before committing"}
            {"observation":"visible state","goal_link":"task complete","goal_trace":"done because final state is verified because all checkpoints passed because final goal is complete","action":"done","summary":"..."}
            {"observation":"visible state","goal_link":"task blocked","goal_trace":"fail because task is impossible because no valid recovery remains because final goal cannot complete","action":"fail","summary":"..."}

            ground_check is a read-only optional confirmation against the stronger
            grounding model (UGround). Use it when multiple plausible targets are
            visible (e.g. several "Close" buttons, ambiguous icons), when a previous
            click landed at coordinates that didn't change the screen, or when you
            need to confirm whether a target is actually visible at all before
            committing. The runtime returns either a bbox + center coords, or
            "not visible". ground_check does NOT click and does NOT change state.
            Do not use it for state-fact questions like "is X running" — current_state
            already answers those; UGround only sees the screenshot.

            For close/quit goals, prefer close_window with the target application/window
            name from current_state. Do this before synthetic GUI routes like Ctrl+Q,
            Alt+F4, clicking the titlebar X, or File → Quit.
            For launch_app, always use the exact full installed app name as
            returned by list_apps — never a short alias, command, slug, or a
            desktop_file path. Call list_apps first when the full name is not
            already known from current_state.
            For open/bring-to-front goals, launch_app and activate_window use the OS
            window manager to focus the target without resizing it. For explicit
            maximize/fullscreen goals, use a separate window action after focus. If
            the app is already open, prefer activate_window with the app/window name
            from current_state rather than clicking dock icons.
            """ + buildAppShortcuts();

    private static String buildAppShortcuts() {
        String gimp = loadResource("GIMP Quickreference.md");
        if (gimp.isBlank()) return "";
        return """

                [keyboard-first rule for dense-icon apps]
                Pixel grounding is unreliable on dense toolbox/dock icon strips
                (~20 px icons crammed in a column). Adjacent icons collapse to
                the same coordinate. When the active app has a known keyboard
                shortcut for the wanted action, emit `hotkey` with that combo
                instead of `click` on the icon. This is mandatory for icon-strip
                targets, optional for buttons large enough to ground reliably.

                [GIMP shortcuts]
                """ + gimp;
    }

    private static String loadResource(String name) {
        try (InputStream in = SeeActPlanner.class.getResourceAsStream("/" + name)) {
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private final LlmClient llm;

    @Value("${nubian.agent.seeact.model:google/gemini-2.5-flash-lite}")
    private String model = "google/gemini-2.5-flash-lite";

    @Value("${nubian.agent.seeact.max-tokens:8192}")
    private int maxTokens = 8192;

    /** Downsample the screenshot to this square size before sending to the planner.
     *  768² is enough for high-level route planning (we don't need pixel-precise
     *  control geometry; UGround / the verifier do that on the full 1024² image).
     *  Cuts ~700-1000 tokens per planner call. Set to 0 to disable. */
    @Value("${nubian.agent.seeact.screenshot-resize:768}")
    private int screenshotResizeTo = 768;

    public SeeActPlanner(LlmClient llm) {
        this.llm = llm;
    }

    public String model() { return LlmClient.costSafeModel(model); }

    public Step next(String userTask, String progressNote, String lastObservation,
            String checklist, String acceptedCheckpoints,
            byte[] rawPng, boolean coordinateFallbackAllowed) {
        return next(userTask, progressNote, lastObservation, checklist, acceptedCheckpoints,
                null, rawPng, coordinateFallbackAllowed);
    }

    public Step next(String userTask, String progressNote, String lastObservation,
            String checklist, String acceptedCheckpoints, String invalidatedRoutes,
            byte[] rawPng, boolean coordinateFallbackAllowed) {
        return next(userTask, progressNote, lastObservation, checklist, acceptedCheckpoints,
                invalidatedRoutes, "", rawPng, coordinateFallbackAllowed);
    }

    public Step next(String userTask, String progressNote, String lastObservation,
            String checklist, String acceptedCheckpoints, String invalidatedRoutes,
            String currentStateText,
            byte[] rawPng, boolean coordinateFallbackAllowed) {
        return next(userTask, progressNote, lastObservation, checklist, acceptedCheckpoints,
                invalidatedRoutes, currentStateText, "", rawPng, coordinateFallbackAllowed);
    }

    public Step next(String userTask, String progressNote, String lastObservation,
            String checklist, String acceptedCheckpoints, String invalidatedRoutes,
            String currentStateText, String goalContractText,
            byte[] rawPng, boolean coordinateFallbackAllowed) {
        return next(userTask, progressNote, lastObservation, checklist, acceptedCheckpoints,
                invalidatedRoutes, currentStateText, goalContractText,
                "", rawPng, coordinateFallbackAllowed);
    }

    public Step next(String userTask, String progressNote, String lastObservation,
            String checklist, String acceptedCheckpoints, String invalidatedRoutes,
            String currentStateText, String goalContractText, String parserContextText,
            byte[] rawPng, boolean coordinateFallbackAllowed) {
        PreparedCall call = prepareCall(userTask, progressNote, lastObservation,
                checklist, acceptedCheckpoints, invalidatedRoutes,
                currentStateText, goalContractText, parserContextText,
                rawPng, coordinateFallbackAllowed);
        LlmClient.Reply reply = llm.chat(LlmClient.Lane.VISION, call.useModel, call.messages, 0.0, maxTokens);
        Step parsed = parseStep(reply.text(), reply);
        log.info("[seeact.planner] {} (model={} tokens={})", parsed, call.useModel, reply.totalTokens());
        return parsed;
    }

    /** Best-of-N: sample {@code n} independent planner candidates at {@code temperature}
     *  and return them all in call order. Caller is responsible for picking the winner
     *  (typically via {@link BestOfNJudge}). When {@code n <= 1} this degenerates to a
     *  single deterministic call at temperature 0.0 — identical to {@link #next}. */
    public List<Step> nextCandidates(int n, double temperature,
            String userTask, String progressNote, String lastObservation,
            String checklist, String acceptedCheckpoints, String invalidatedRoutes,
            String currentStateText, String goalContractText,
            byte[] rawPng, boolean coordinateFallbackAllowed) {
        if (n <= 1) {
            return List.of(next(userTask, progressNote, lastObservation, checklist, acceptedCheckpoints,
                    invalidatedRoutes, currentStateText, goalContractText, rawPng, coordinateFallbackAllowed));
        }
        PreparedCall call = prepareCall(userTask, progressNote, lastObservation,
                checklist, acceptedCheckpoints, invalidatedRoutes,
                currentStateText, goalContractText, "",
                rawPng, coordinateFallbackAllowed);
        return nextCandidatesFromCall(n, temperature, call);
    }

    public List<Step> nextCandidates(int n, double temperature,
            String userTask, String progressNote, String lastObservation,
            String checklist, String acceptedCheckpoints, String invalidatedRoutes,
            String currentStateText, String goalContractText, String parserContextText,
            byte[] rawPng, boolean coordinateFallbackAllowed) {
        if (n <= 1) {
            return List.of(next(userTask, progressNote, lastObservation, checklist, acceptedCheckpoints,
                    invalidatedRoutes, currentStateText, goalContractText, parserContextText,
                    rawPng, coordinateFallbackAllowed));
        }
        PreparedCall call = prepareCall(userTask, progressNote, lastObservation,
                checklist, acceptedCheckpoints, invalidatedRoutes,
                currentStateText, goalContractText, parserContextText,
                rawPng, coordinateFallbackAllowed);
        return nextCandidatesFromCall(n, temperature, call);
    }

    private List<Step> nextCandidatesFromCall(int n, double temperature, PreparedCall call) {
        List<java.util.concurrent.CompletableFuture<Step>> futures = new ArrayList<>(n);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    LlmClient.Reply r = llm.chat(LlmClient.Lane.VISION, call.useModel, call.messages, temperature, maxTokens);
                    Step s = parseStep(r.text(), r);
                    log.info("[seeact.planner.bon] cand[{}] {} (model={} tokens={})", idx, s, call.useModel, r.totalTokens());
                    return s;
                }, pool));
            }
            List<Step> out = new ArrayList<>(n);
            for (java.util.concurrent.CompletableFuture<Step> f : futures) {
                try { out.add(f.get()); }
                catch (Exception e) {
                    log.warn("[seeact.planner.bon] candidate failed: {}", e.toString());
                    out.add(Step.parseError("bon_candidate_failed: " + e.getMessage()));
                }
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    private record PreparedCall(List<LlmClient.Message> messages, String useModel) { }

    private PreparedCall prepareCall(String userTask, String progressNote, String lastObservation,
            String checklist, String acceptedCheckpoints, String invalidatedRoutes,
            String currentStateText, String goalContractText, String parserContextText,
            byte[] rawPng, boolean coordinateFallbackAllowed) {
        StringBuilder body = new StringBuilder();
        body.append("[user goal]\n").append(userTask == null ? "" : userTask.trim()).append('\n');
        if (goalContractText != null && !goalContractText.isBlank()) {
            // Authoritative parsed contract. The planner MUST treat this as the
            // source of truth for cardinality and spatial relations — never
            // re-derive them from the prose [user goal] above.
            body.append('\n').append(goalContractText.trim()).append('\n');
        }
        if (checklist != null && !checklist.isBlank()) {
            body.append("\n[active checkpoint]\n").append(checklist.trim()).append('\n');
            body.append("Every non-terminal action, including recovery or prep, must include this checkpoint_id exactly.\n");
            if (acceptedCheckpoints != null && !acceptedCheckpoints.isBlank()) {
                body.append("\n[accepted checkpoints]\n").append(acceptedCheckpoints.trim()).append('\n');
                body.append("Do not redo these unless the screenshot proves they were undone.\n");
            }
        } else {
            body.append("\n[checklist]\nnone yet; include checklist in this response.\n");
        }
        if (currentStateText != null && !currentStateText.isBlank()) {
            // Authoritative OS-level snapshot. The planner used to have only the
            // screenshot, which made it conflate "I can't see X on this screen"
            // with "X doesn't exist". GNOME's Activities Overview hides backgrounded
            // windows; the screenshot's silence is not evidence of absence. Pipe
            // current_state in so state-fact judgments ("is X running") have an
            // authoritative answer beyond visual interpretation.
            body.append("\n[current_state] (authoritative OS snapshot — running windows + installed apps)\n")
                    .append(currentStateText.trim()).append('\n');
        }
        if (invalidatedRoutes != null && !invalidatedRoutes.isBlank()) {
            body.append('\n').append(invalidatedRoutes.trim()).append('\n');
        }
        if (parserContextText != null && !parserContextText.isBlank()) {
            body.append('\n').append(parserContextText.trim()).append('\n');
        }
        if (progressNote != null && !progressNote.isBlank()) {
            body.append("\n[progress]\n").append(progressNote.trim()).append('\n');
        }
        if (lastObservation != null && !lastObservation.isBlank()) {
            body.append("\n[last observation]\n").append(lastObservation.trim()).append('\n');
        }
        if (coordinateFallbackAllowed) {
            body.append("\n[pointer contract]\nUGround is disabled. Pointer actions must include x and y pixels from the screenshot. "
                    + "type_text is not a pointer action; when the correct field is focused, emit type_text with text only.\n");
        } else {
            body.append("\n[pointer contract]\nUGround is enabled. Pointer actions must use element_description, not x/y. "
                    + "screen_region is required and must be one of: top-left, top-center, top-right, middle-left, "
                    + "center, middle-right, bottom-left, bottom-center, bottom-right. "
                    + "Pick the region that matches where the target is on the current screen — UGround uses it to "
                    + "disambiguate similar elements (e.g. multiple icons with the same label in different parts of the screen).\n");
        }
        body.append("\n[next]\nReturn a JSON object only. The first character must be { and the last character must be }.\n");

        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(SYSTEM));
        byte[] plannerPng = rawPng;
        if (plannerPng != null && plannerPng.length > 0 && screenshotResizeTo > 0) {
            byte[] resized = downsamplePng(plannerPng, screenshotResizeTo);
            if (resized != null) plannerPng = resized;
        }
        if (plannerPng != null && plannerPng.length > 0) {
            messages.add(LlmClient.Message.userImage(body.toString(), plannerPng));
        } else {
            messages.add(LlmClient.Message.user(body.toString()));
        }

        String useModel = LlmClient.costSafeModel(model);
        return new PreparedCall(messages, useModel);
    }

    static Step parseStep(String raw) {
        return parseStep(raw, null);
    }

    static Step parseStep(String raw, LlmClient.Reply reply) {
        if (raw == null || raw.isBlank()) {
            String suffix = reply == null ? "" : " (finish_reason=" + reply.finishReason()
                    + ", prompt_tokens=" + reply.promptTokens()
                    + ", completion_tokens=" + reply.completionTokens()
                    + ", total_tokens=" + reply.totalTokens()
                    + ", reasoning_chars=" + reply.reasoningChars() + ")";
            return Step.parseError("seeact planner returned empty content" + suffix);
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Step.parseError("seeact planner did not produce a JSON object: " + truncate(text, 240));
        }
        try {
            JsonNode n = MAPPER.readTree(text.substring(start, end + 1));
            String action = n.path("action").asText("").trim();
            JsonNode batchActions = n.path("actions");
            // For plan_waypoints, the planner emits a `waypoints` array — reuse
            // the batchActions slot to carry it through to runtime without
            // adding another Step field.
            if ("plan_waypoints".equals(action) || "plan".equals(action) || "waypoint_plan".equals(action)) {
                JsonNode wp = n.path("waypoints");
                if (wp.isArray() && wp.size() > 0) batchActions = wp;
                action = "plan_waypoints";
            }
            boolean hasBatch = batchActions != null && batchActions.isArray() && batchActions.size() > 0;
            if (action.isEmpty() && hasBatch) {
                action = "batch";  // canonical action name when planner emits actions[] without a top-level action
            }
            if (action.isEmpty()) {
                return Step.parseError("seeact planner JSON missing action: " + truncate(text, 240));
            }
            return new Step(
                    action,
                    firstText(n, "element_description", "description", "target"),
                    firstText(n, "screen_region", "region"),
                    firstText(n, "text_to_type", "textToType", "text", "value"),
                    optString(n, "mode"),
                    optString(n, "combo"),
                    intOrNull(n, "dx"),
                    intOrNull(n, "dy"),
                    intOrNull(n, "ms"),
                    firstText(n, "target", "query", "name"),
                    optString(n, "desktop_file"),
                    optString(n, "exec"),
                    optString(n, "path"),
                    optString(n, "content"),
                    optString(n, "summary"),
                    optString(n, "reason"),
                    firstText(n, "checkpoint_id", "checkpointId", "active_checkpoint_id"),
                    firstText(n, "observation", "visible_state", "what_i_see"),
                    firstText(n, "goal_link", "goalLink", "roi", "why_goal"),
                    firstText(n, "goal_trace", "goalTrace", "action_trace", "trace"),
                    firstText(n, "assumption", "assumes"),
                    firstText(n, "verified_by", "verifiedBy", "evidence"),
                    intOrNull(n, "x"),
                    intOrNull(n, "y"),
                    parseChecklist(n),
                    parseSubtasks(n),
                    parseChecklistUpdates(n),
                    null,
                    firstText(n, "from_description", "fromDescription", "from"),
                    firstText(n, "to_description", "toDescription", "to"),
                    intOrNull(n, "from_x"),
                    intOrNull(n, "from_y"),
                    intOrNull(n, "to_x"),
                    intOrNull(n, "to_y"),
                    intOrNull(n, "box"),
                    intOrNull(n, "to_box"),
                    doubleOrNull(n, "duration_seconds"),
                    hasBatch ? batchActions : null);
        } catch (Exception ex) {
            return Step.parseError("seeact planner JSON parse failed: " + ex.getMessage());
        }
    }

    private static List<ChecklistItem> parseChecklist(JsonNode n) {
        JsonNode arr = n.path("checklist");
        if (!arr.isArray() || arr.isEmpty()) return List.of();
        List<ChecklistItem> out = new ArrayList<>();
        for (JsonNode item : arr) {
            String id = firstText(item, "id", "key");
            if (id == null || id.isBlank()) id = String.valueOf(out.size() + 1);
            String text = firstText(item, "text", "item", "goal", "description");
            if (text == null || text.isBlank()) continue;
            out.add(new ChecklistItem(id.trim(), text.trim(), item.path("done").asBoolean(false)));
            if (out.size() >= 6) break;
        }
        return List.copyOf(out);
    }

    private static List<ChecklistUpdate> parseChecklistUpdates(JsonNode n) {
        JsonNode arr = n.path("checklist_updates");
        if (!arr.isArray() || arr.isEmpty()) arr = n.path("updates");
        if (!arr.isArray() || arr.isEmpty()) return List.of();
        List<ChecklistUpdate> out = new ArrayList<>();
        for (JsonNode item : arr) {
            String id = firstText(item, "id", "key");
            if (id == null || id.isBlank()) continue;
            out.add(new ChecklistUpdate(
                    id.trim(),
                    item.path("done").asBoolean(true),
                    firstText(item, "evidence", "reason")));
            if (out.size() >= 6) break;
        }
        return List.copyOf(out);
    }

    private static List<ChecklistItem> parseSubtasks(JsonNode n) {
        JsonNode arr = n.path("subtasks");
        if (!arr.isArray() || arr.isEmpty()) arr = n.path("subchecklist");
        if (!arr.isArray() || arr.isEmpty()) return List.of();
        List<ChecklistItem> out = new ArrayList<>();
        for (JsonNode item : arr) {
            String text = firstText(item, "text", "item", "goal", "description");
            if (text == null || text.isBlank()) continue;
            out.add(new ChecklistItem(String.valueOf(out.size() + 1), text.trim(), false));
            if (out.size() >= 3) break;
        }
        return List.copyOf(out);
    }

    private static String firstText(JsonNode n, String... keys) {
        for (String key : keys) {
            String value = optString(n, key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String optString(JsonNode n, String key) {
        if (!n.has(key) || n.get(key).isNull()) return null;
        return n.get(key).asText(null);
    }

    private static Integer intOrNull(JsonNode n, String key) {
        if (!n.has(key) || n.get(key).isNull()) return null;
        return n.get(key).asInt();
    }

    private static Double doubleOrNull(JsonNode n, String key) {
        if (!n.has(key) || n.get(key).isNull()) return null;
        return n.get(key).asDouble();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Bicubic downscale to {@code target}×{@code target} square PNG for the
     *  planner only. The verifier and UGround keep the full-resolution shot. */
    private static byte[] downsamplePng(byte[] png, int target) {
        if (png == null || png.length == 0 || target <= 0) return png;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
            if (src == null) return png;
            if (src.getWidth() == target && src.getHeight() == target) return png;
            BufferedImage dst = new BufferedImage(target, target, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(src, 0, 0, target, target, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32 * 1024, png.length / 2));
            ImageIO.write(dst, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            log.warn("[seeact.planner.resize] failed; sending raw bytes: {}", ex.toString());
            return png;
        }
    }

    public record Step(
            String action,
            String elementDescription,
            String screenRegion,
            String textToType,
            String mode,
            String combo,
            Integer dx, Integer dy,
            Integer ms,
            String target,
            String desktopFile,
            String exec,
            String path,
            String content,
            String summary,
            String reason,
            String checkpointId,
            String observation,
            String goalLink,
            String goalTrace,
            String assumption,
            String verifiedBy,
            Integer x, Integer y,
            List<ChecklistItem> checklist,
            List<ChecklistItem> subtasks,
            List<ChecklistUpdate> checklistUpdates,
            String parseError,
            String fromDescription,
            String toDescription,
            Integer fromX, Integer fromY,
            Integer toX, Integer toY,
            Integer fromBox, Integer toBox,
            Double durationSeconds,
            JsonNode batchActions) {
        public Step {
            checklist = checklist == null ? List.of() : List.copyOf(checklist);
            subtasks = subtasks == null ? List.of() : List.copyOf(subtasks);
            checklistUpdates = checklistUpdates == null ? List.of() : List.copyOf(checklistUpdates);
        }

        public boolean isBatch() {
            return batchActions != null && batchActions.isArray() && batchActions.size() > 0;
        }

        public boolean isPointerAction() {
            return "click".equals(action) || "double_click".equals(action)
                    || "right_click".equals(action);
        }

        public boolean isClickOnly() {
            return "click".equals(action) || "double_click".equals(action) || "right_click".equals(action);
        }

        public boolean isType() {
            return "type_text".equals(action) || "type".equals(action);
        }

        public boolean isTerminal() {
            return "done".equals(action) || "fail".equals(action);
        }

        public boolean hasCoords() {
            return x != null && y != null;
        }

        public boolean isParseError() {
            return parseError != null;
        }

        static Step parseError(String why) {
            return new Step(
                    "format_error",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null, null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    why,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null, null,
                    List.of(),
                    List.of(),
                    List.of(),
                    why,
                    null, null,
                    null, null, null, null,
                    null, null,
                    null,
                    null);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Step[").append(action);
            if (elementDescription != null) sb.append(" element=").append(truncate(elementDescription, 60));
            if (x != null && y != null) sb.append(" xy=").append(x).append(',').append(y);
            if (textToType != null) sb.append(" text=").append(truncate(textToType, 40));
            if (combo != null) sb.append(" combo=").append(combo);
            if (target != null) sb.append(" target=").append(truncate(target, 60));
            if (desktopFile != null) sb.append(" desktop_file=").append(truncate(desktopFile, 60));
            if (exec != null) sb.append(" exec=").append(truncate(exec, 60));
            if (!checklist.isEmpty()) sb.append(" checklist=").append(checklist.size());
            if (!subtasks.isEmpty()) sb.append(" subtasks=").append(subtasks.size());
            if (!checklistUpdates.isEmpty()) sb.append(" updates=").append(checklistUpdates.size());
            if (summary != null) sb.append(" summary=").append(truncate(summary, 80));
            if (reason != null) sb.append(" reason=").append(truncate(reason, 80));
            if (checkpointId != null) sb.append(" checkpoint=").append(truncate(checkpointId, 20));
            if (observation != null) sb.append(" observation=").append(truncate(observation, 80));
            if (goalLink != null) sb.append(" goal=").append(truncate(goalLink, 40));
            if (goalTrace != null) sb.append(" trace=").append(truncate(goalTrace, 80));
            if (assumption != null) sb.append(" assumption=").append(truncate(assumption, 60));
            if (verifiedBy != null) sb.append(" verified=").append(truncate(verifiedBy, 60));
            return sb.append(']').toString();
        }
    }

    public record ChecklistItem(String id, String text, boolean done) {}

    public record ChecklistUpdate(String id, boolean done, String evidence) {}
}
