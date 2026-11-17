# MeshLink UI/UX design system

## Visual direction
MeshLink uses a dark-first, technical interface: obsidian surfaces, restrained cyan/teal accents, clear semantic status colors, readable Inter body text, Space Grotesk headings and JetBrains Mono for device IDs and diagnostic values. The light theme remains supported.

## Shared component behavior
- `GlassPanel` is the common surface for dashboards, settings, chat lists, diagnostics and feature screens. It uses a subtle accent edge, soft elevation, a restrained vertical surface gradient, content-size animation and a short fade/vertical entrance.
- `SectionTitle` provides consistent hierarchy and a small gradient accent rule.
- `PulseStatusDot` animates only while active; inactive indicators stay static to avoid unnecessary animation work.
- Dashboard metric values transition when their underlying values change.
- Navigation icons use a restrained spring scale on selection. Navigation bars and rails use the elevated surface palette.

## Screen polish included in this pass
- Shared panel and section-title styling applies across screens that already use the shared components.
- Home dashboard metrics animate as values update; dashboard layout responds to content changes.
- Profile screen has a dedicated local-profile hero, clearer editable fields, privacy grouping and an explicit local-storage explanation.
- Top app bar, navigation rail and bottom navigation use consistent surface colors and selected-state icon motion.
- Existing controls, route names, callbacks, test tags and data models are preserved.
- Private and group chat composers follow the Android keyboard inset, cap drafts at 4,000 characters, show a live character count and disable sending when the draft is blank or the conversation target is unavailable.
- Chat timelines animate to the newest message when the message count changes; message bubbles use a subtle directional corner, outline and content-size transition.

## UX and accessibility guardrails
- Motion is short and functional; avoid continuous animation for static/offline states.
- Keep status meaning in text and color, not color alone.
- Do not use animation to imply a peer is connected or a transfer is complete; those states must come from transport/application state.
- Preserve existing test tags and touch targets when refining controls.
- Keep permission prompts contextual and user-initiated.
- Avoid false "live", "secure", "delivered" or "connected" claims when only discovery observations are available.

## Remaining design QA
Review every route on a compact phone and a wide screen, with both light and dark themes and Android font scaling. Verify keyboard insets on chat and forms, TalkBack labels, contrast, large text wrapping, empty/loading/error states, and animation behavior under Android's reduced-motion settings. Physical-device screenshots and visual regression checks are still required before claiming pixel-perfect polish.
