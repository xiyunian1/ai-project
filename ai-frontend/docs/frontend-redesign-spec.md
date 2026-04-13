# WMS AI Frontend Redesign Spec

## 1. Current Diagnosis

The current frontend already has a usable route structure, but the visual system is fragmented:

- The auth pages use a warm decorative style, while the working pages fall back to generic panel-and-form styling. Brand continuity breaks between [Login.vue](c:/Users/1/Desktop/ai数据库/ai-project/ai-frontend/src/views/Login.vue), [Register.vue](c:/Users/1/Desktop/ai数据库/ai-project/ai-frontend/src/views/Register.vue), [Chat.vue](c:/Users/1/Desktop/ai数据库/ai-project/ai-frontend/src/views/Chat.vue), and [Inventory.vue](c:/Users/1/Desktop/ai数据库/ai-project/ai-frontend/src/views/Inventory.vue).
- Layout hierarchy is weak on business pages: session navigation, header, content, tools, and form controls all compete at similar weight.
- Styles are page-scoped and repeated instead of tokenized. The same gradient, radius, and border patterns are copied across views, but there is no shared design language.
- Mobile adaptation is functional but simplistic. On small screens the sidebar disappears entirely, which reduces navigation clarity for a multi-feature product.
- The product has enough capability breadth to justify a true workspace shell: chat, analysis, inventory, workflow, and document processing should feel like one system, not five standalone pages.

The redesign target is therefore not "more decoration". It is a unified, high-trust operational workspace with premium restraint.

## 2. Design Manifesto

### 2.1 Core Design Idea

Use one visual direction across the entire product:

- Tone: quiet, precise, intelligent
- Reference quality bar: Linear clarity + Notion restraint + Figma spatial balance
- Product personality: enterprise-grade AI operations console, not consumer SaaS marketing

Three principles:

1. Frame first: every page should inherit the same shell and spacing rhythm before any page-specific styling.
2. One accent, multiple signals: one brand color drives primary actions; auxiliary colors only communicate state and context.
3. Depth by layers, not ornament: use subtle glass, restrained gradients, and micro-shadows to separate planes without looking glossy or cheap.

### 2.2 Information Architecture

Use a persistent application shell for all authenticated pages:

- Left rail: brand, feature nav, session/history, account entry
- Top bar: page title, context summary, primary page action
- Main canvas: conversation, analysis result, inventory view, workflow content, or document editor
- Right utility drawer on demand: settings, metadata, SQL trace, task detail

Recommended route positioning:

- `/login`, `/register`: entry gateway
- `/chat`: default workspace home after login
- `/analysis`: data analysis workspace
- `/inventory`: inventory insight dashboard
- `/workflow`: process guidance center
- `/document-process`: document workspace

## 3. Design System

### 3.1 Color System

Use one primary color, three support colors, and a full neutral scale.

#### Light Mode

| Token | Value | Usage |
| --- | --- | --- |
| `--brand-500` | `#2563EB` | Primary buttons, active state, links |
| `--brand-600` | `#1D4ED8` | Hover/pressed |
| `--teal-500` | `#0F9F8D` | Success, live/connected status |
| `--amber-500` | `#D97706` | Warning, pending state |
| `--rose-500` | `#D1435B` | Error, destructive state |
| `--bg-canvas` | `#F5F7FB` | Global app background |
| `--bg-surface-1` | `rgba(255,255,255,0.78)` | Main glass panels |
| `--bg-surface-2` | `#FFFFFF` | Solid cards, inputs |
| `--bg-muted` | `#EEF2F8` | Secondary surfaces |
| `--border-subtle` | `rgba(15,23,42,0.08)` | Default border |
| `--border-strong` | `rgba(15,23,42,0.14)` | Active border |
| `--text-primary` | `#0F172A` | Main text |
| `--text-secondary` | `#475569` | Secondary text |
| `--text-tertiary` | `#64748B` | Hint/caption |
| `--text-inverse` | `#F8FAFC` | Text on dark/accent surfaces |

#### Dark Mode

| Token | Value | Usage |
| --- | --- | --- |
| `--brand-500` | `#5B8CFF` | Primary buttons, links |
| `--brand-600` | `#7AA2FF` | Hover/focus emphasis |
| `--teal-500` | `#2FC6B2` | Success, active telemetry |
| `--amber-500` | `#F0A43B` | Warning |
| `--rose-500` | `#F06A83` | Error |
| `--bg-canvas` | `#0B1020` | Global background |
| `--bg-surface-1` | `rgba(15,23,42,0.72)` | Glass panels |
| `--bg-surface-2` | `#111827` | Solid cards |
| `--bg-muted` | `#182235` | Secondary surfaces |
| `--border-subtle` | `rgba(148,163,184,0.16)` | Default border |
| `--border-strong` | `rgba(148,163,184,0.28)` | Active border |
| `--text-primary` | `#E5EDF7` | Main text |
| `--text-secondary` | `#B2C0D4` | Secondary text |
| `--text-tertiary` | `#8A9AB3` | Hint/caption |
| `--text-inverse` | `#08111F` | Text on bright accent surfaces |

#### Background and Gradient Rules

- Main canvas gradient:
  - Light: `radial-gradient(circle at top left, rgba(37,99,235,0.10), transparent 34%), radial-gradient(circle at top right, rgba(15,159,141,0.08), transparent 28%), #F5F7FB`
  - Dark: `radial-gradient(circle at top left, rgba(91,140,255,0.18), transparent 30%), radial-gradient(circle at top right, rgba(47,198,178,0.10), transparent 24%), #0B1020`
- Glass surface:
  - Light: `background: rgba(255,255,255,0.72); backdrop-filter: blur(18px);`
  - Dark: `background: rgba(17,24,39,0.72); backdrop-filter: blur(18px);`
- Avoid multicolor consumer gradients on buttons or cards. Accent gradients only appear in hero halo, focus ring glow, or data visualization highlights.

#### Accessibility Rules

- Text/background contrast must remain `>= 4.5:1` for body text.
- Large headings and numeric stat text must remain `>= 3:1`.
- Never use color as the only status differentiator. Pair with icon, label, or border pattern.

### 3.2 Typography

Font stack:

- Chinese: `"PingFang SC", "Hiragino Sans GB", "Source Han Sans SC", "Microsoft YaHei", sans-serif`
- English and numbers: `"Inter", "SF Pro Display", "Segoe UI", sans-serif`
- Monospace: `"JetBrains Mono", "SFMono-Regular", "Consolas", monospace`

Type scale:

| Level | Size | Weight | Line Height | Usage |
| --- | --- | --- | --- | --- |
| Display | `40px` | `700` | `1.1` | Auth hero / empty state heading |
| H1 | `32px` | `600` | `1.2` | Page-level title |
| H2 | `24px` | `600` | `1.25` | Section heading |
| H3 | `20px` | `600` | `1.3` | Card title / dialog title |
| Title | `18px` | `600` | `1.35` | Sub-panels |
| Body | `16px` | `400` | `1.6` | Main content |
| Body S | `14px` | `400` | `1.6` | Secondary text |
| Caption | `12px` | `500` | `1.5` | Metadata / helper text |
| Mono S | `13px` | `500` | `1.6` | SQL / code / log |

Rules:

- Titles use negative letter spacing only for English-heavy headings: `-0.02em`.
- Data cards align numbers on tabular figures if available.
- Long paragraphs must max out at `64-72` Chinese characters per line in desktop reading areas.

### 3.3 Layout and Grid

#### Global Grid

- Desktop `>= 1440px`: 12 columns, `80px` outer margin, `24px` gutter, max content width `1440px`
- Desktop `1280-1439px`: 12 columns, `48px` outer margin, `24px` gutter
- Tablet `768-1279px`: 8 columns, `24px` outer margin, `20px` gutter
- Mobile `375-767px`: 4 columns, `16px` outer margin, `16px` gutter

#### Shell Dimensions

- Left rail width:
  - Expanded: `272px`
  - Collapsed tablet rail: `76px`
  - Mobile: replaced by bottom nav or slide drawer, not hidden with no replacement
- Top bar height: `72px`
- Composer/input dock: min `88px`, max `180px` when expanded
- Main content vertical spacing: `24px`

#### Spacing Scale

- `4, 8, 12, 16, 20, 24, 32, 40, 48, 64`

Use only these increments to maintain rhythm.

### 3.4 Radius, Border, Shadow, Blur

#### Radius

- `--radius-xs: 4px`
- `--radius-sm: 6px`
- `--radius-md: 8px`

Component mapping:

- Button: `8px`
- Input/select: `8px`
- Card: `8px`
- Tooltip/menu: `6px`
- Tag/chip: `999px`

#### Border

- Default: `1px solid var(--border-subtle)`
- Active/focus container: `1px solid color-mix(in srgb, var(--brand-500) 44%, transparent)`

#### Shadow

- `--shadow-xs: 0 1px 2px rgba(15,23,42,0.04)`
- `--shadow-sm: 0 4px 12px rgba(15,23,42,0.06)`
- `--shadow-md: 0 8px 24px rgba(15,23,42,0.10)`

Use `xs` and `sm` by default. `md` only for dialogs, floating drawers, or sticky composer.

#### Blur

- Page glass shell: `16-18px`
- Hover glass layers: `10-12px`
- Never stack multiple blurred layers on the same axis.

### 3.5 Motion

Timing:

- Hover: `180ms`
- Click/press: `120ms`
- Panel enter/exit: `220ms`
- Page transition: `260ms`

Easing:

- Standard: `cubic-bezier(0.22, 1, 0.36, 1)`
- Exit: `cubic-bezier(0.4, 0, 1, 1)`

Motion rules:

- Hover only lifts `1px` or changes border/background.
- Do not animate layout shifts on dense data views.
- Loading uses skeleton shimmer or pulse, not large spinners.
- Respect `prefers-reduced-motion`.

### 3.6 Component Specification

#### Primary Button

- Height: `40px`
- Padding: `0 16px`
- Radius: `8px`
- Background: `var(--brand-500)`
- Hover: `background: var(--brand-600)`
- Focus ring: `0 0 0 4px rgba(37,99,235,0.14)`

#### Secondary Button

- Height: `40px`
- Background: `var(--bg-surface-2)`
- Border: `1px solid var(--border-subtle)`
- Text: `var(--text-primary)`
- Hover: `background: color-mix(in srgb, var(--bg-muted) 72%, white)`

#### Input / Textarea

- Height: `44px`
- Padding: `0 14px`
- Radius: `8px`
- Background: `var(--bg-surface-2)`
- Placeholder: `var(--text-tertiary)`
- Focus: brand border + `4px` low-opacity halo
- Invalid: `--rose-500` border + inline helper text

#### Card

- Radius: `8px`
- Border: `1px solid var(--border-subtle)`
- Background: `var(--bg-surface-1)` or `var(--bg-surface-2)`
- Padding: `20px`
- Shadow: `var(--shadow-sm)`

#### Table

- Header height: `44px`
- Row height: `48px`
- Zebra rows disabled by default
- Hover row fill: `color-mix(in srgb, var(--brand-500) 4%, transparent)`
- Sticky header on data-dense pages

#### Status Pattern

- Loading: skeleton blocks + text `"正在同步数据"`
- Empty: icon + title + one-line explanation + one primary CTA
- Error: compact inline banner + retry CTA
- Success: non-blocking toast + subtle confirmation icon

## 4. Key Page Redesign

### 4.1 Entry Home / Auth Gateway

Applicable pages:

- `/login`
- `/register`

Current issue:

- The current auth card is isolated and decorative, but it does not connect visually to the product workspace.

Redesign:

- Use a split layout on desktop:
  - Left `7/12`: product narrative panel
  - Right `5/12`: auth card
- Left panel content:
  - Product title: `WMS AI Workspace`
  - One-line positioning: `面向仓储运营、数据分析与文档处理的一体化智能工作台`
  - Three capability rows: `智能问答`, `库存洞察`, `文档处理`
  - Background: subtle grid + soft brand halo + one frosted metric strip
- Right auth card:
  - Width `420-460px`
  - Surface: glass card with `8px` radius, `18px` blur
  - Form fields vertically spaced at `16px`
  - Primary CTA full width
  - Secondary link button below form

Motion:

- Card enter: fade + translateY `12px`, `220ms`
- Capability rows stagger in at `40ms` intervals
- Input focus halo expands in `180ms`

Mobile:

- Collapse to single column
- Move narrative panel above form as a condensed brand strip
- Keep all auth actions visible without extra scroll on `812px` height devices

### 4.2 Smart Workspace Home

Applicable page:

- `/chat`

Current issue:

- The current page has the right primitives, but hierarchy is weak: sidebar, header, bubbles, profile panel, and composer do not feel part of the same system.

Redesign shell:

- Left rail:
  - Brand lockup at top
  - New conversation CTA
  - Feature navigation group
  - Session history list
  - User/account dock at bottom
- Top bar:
  - Title + session breadcrumb on the left
  - Active mode chips on the right: `标准问答`, `数据库问答`, `图像输入`
- Main canvas:
  - Empty state becomes a command-center hero with 3 suggested prompts
  - Conversation feed sits in a `760-880px` readable column centered in the canvas
  - Assistant messages use solid surface cards
  - User messages use tinted brand surface with stronger contrast
- Right utility drawer:
  - Replace the current floating profile panel with a consistent slide-over drawer
  - Tabs: `账号`, `功能`, `偏好`

Key specs:

- Sidebar background:
  - Light: `rgba(248,250,252,0.72)`
  - Dark: `rgba(10,15,27,0.82)`
- Sidebar border-right: `1px solid var(--border-subtle)`
- Session item height: `40px`
- Active session: brand left bar `2px` + raised surface
- Message gap: `20px`
- Message avatar size: `32px`
- Bubble max width:
  - Desktop: `min(860px, 72%)`
  - Mobile: `calc(100vw - 64px)`

Micro-interactions:

- Session hover: surface tint + `1px` translateX
- Send button press: scale `0.98`
- New message insertion: fade + rise `180ms`
- Drawer open: slide from right `220ms`

### 4.3 Analysis Workspace

Applicable page:

- `/analysis`

Redesign direction:

- Reuse the same shell as `/chat` to maintain continuity.
- Add an analysis-specific top context strip:
  - DB connection status chip
  - Current data source
  - Allowed tables summary
  - `配置连接` secondary button
- Result structure:
  - Answer card
  - SQL trace card
  - Insight cards for metrics if response contains aggregates
  - Optional collapsible execution notes

Layout:

- Top bar stays `72px`
- Connection strip sits directly below top bar at `56px`
- Analysis result stack uses `16px` vertical gap
- SQL trace defaults to collapsed, but expanded state opens inline below answer

Dialog redesign:

- DB config becomes a proper modal with two-column form on desktop and one-column on mobile
- Use sections:
  - Connection
  - Auth
  - Scope
  - Safety
- Footer keeps `取消` and `保存配置`

State design:

- Connected: teal chip + server summary
- Default: neutral chip + `"使用默认连接"`
- Invalid config: inline error banner under affected field group

### 4.4 Inventory Insight Dashboard

Applicable page:

- `/inventory`

Current issue:

- The page is currently two stacked tables with low decision support value.

Redesign:

- Convert page into a dashboard with three layers:
  - Top KPI strip
  - Filter/search panel
  - Insight tables/cards

Desktop layout:

- Row 1:
  - `4 x` KPI cards: `总 SKU`, `低库存数量`, `高风险供应商`, `近 7 日波动`
- Row 2:
  - Left `8/12`: search and result table
  - Right `4/12`: low-stock warning card + rule tips
- Row 3:
  - Full-width table or timeline if more data is added later

Key components:

- Search bar with inline filter pills
- KPI card:
  - Number `32px/600`
  - Label `14px/500`
  - Delta text `12px/500`
- Table rows get status tags:
  - `正常`
  - `临界`
  - `缺货`

Interaction:

- Search submit morphs button to loading state without layout shift
- Table empty state suggests example query text
- Warning card pulses border softly only when fresh low-stock data is received

### 4.5 Workflow Guidance Center

Applicable page:

- `/workflow`

Current issue:

- Static ordered lists do not feel like an active workflow tool.

Redesign:

- Recast the page as a process board:
  - Tabs: `入库`, `出库`, `异常处理`
  - Each tab shows timeline cards or checklist stages
- Each stage card contains:
  - Stage title
  - Risk note
  - Recommended automation action
  - Related artifact or policy if available later

Desktop layout:

- Left `3/12`: process navigation and risk legend
- Right `9/12`: process timeline

Card spec:

- Stage number chip `28px`
- Card padding `20px`
- Connector line opacity `0.18`
- Risk badge colors follow status tokens, not extra palette inventions

### 4.6 Document Processing Workspace

Applicable page:

- `/document-process`

Current issue:

- The workflow is useful, but the page still reads like a form plus raw preview block.

Redesign:

- Desktop split canvas:
  - Left `5/12`: upload zone + processing rules + file meta
  - Right `7/12`: source preview / processed preview / diff tabs
- Upload zone:
  - Large drop surface with dashed border
  - Secondary text explains supported formats and approximate processing time
- Requirement input:
  - Prompt editor with suggested prompt chips
- Result area:
  - Tabs: `原文预览`, `处理结果`, `下载记录`
  - Use monospace only for raw text preview; regular content preview remains standard body text

State flow:

- No file: centered upload empty state
- File selected: show file card + preview
- Processing: progress rail with three steps `上传`, `处理`, `生成下载`
- Success: non-blocking toast + sticky download CTA
- Fallback mode: warning banner explaining format fidelity risk

### 4.7 Unified Empty / Loading / Error States

Apply across all core pages.

#### Empty State

- Icon size: `32px`
- Title: `20px/600`
- Body: `14px/400`
- One primary CTA max

#### Loading State

- Skeleton card height matches final content
- Loading label placed at top-right or inline beneath header
- Never replace the whole page with spinner if partial skeleton is possible

#### Error State

- Inline banner:
  - Background light mode: `rgba(209,67,91,0.08)`
  - Border: `rgba(209,67,91,0.18)`
  - Text: `#B4233D`
- Must include one next action: `重试`, `返回默认配置`, or `重新登录`

## 5. Responsive Strategy

### Desktop 1920 / 1440

- Keep centered content column for reading
- Sidebar remains persistent
- Secondary panels may float or dock on the right

### Tablet 768

- Sidebar collapses into icon rail
- Right drawer becomes full-height overlay
- Inventory dashboard compresses from 4 KPI cards to 2 columns

### Mobile 375 / 414

- Replace hidden sidebar with bottom nav or slide-in drawer
- Keep page title, primary action, and composer always accessible
- Reduce non-essential chrome before cutting functionality
- Data tables switch to stacked card rows if less than 5 columns fit legibly

## 6. Direct Implementation Guidance

### 6.1 Recommended Frontend Stack

Recommended path for this repository:

- Keep `Vue 3 + Vite + Vue Router + Electron`
- Introduce `Tailwind CSS 4` for layout, spacing, and token-driven utilities
- Keep a small token layer in `src/styles/tokens.css`
- Keep component styles in `src/components/ui/*` rather than view-local duplication
- De-prioritize `Element Plus` for shell and premium surfaces; if retained, use it only for low-visibility primitives

Reason:

- The current project barely uses `Element Plus`, so forcing the redesign through it adds theme friction without real benefit.
- Tailwind utility layout + CSS variable tokens gives faster implementation while still supporting a custom premium look.

### 6.2 File Structure Recommendation

```text
src/
  components/
    ui/
      AppShell.vue
      SidebarNav.vue
      TopBar.vue
      GlassCard.vue
      StatusBanner.vue
      EmptyState.vue
      SkeletonBlock.vue
      ComposerBox.vue
  styles/
    tokens.css
    base.css
    themes.css
    motion.css
  views/
    Login.vue
    Register.vue
    Chat.vue
    Analysis.vue
    Inventory.vue
    Workflow.vue
    DocumentProcess.vue
```

### 6.3 Figma and Handoff

- Build tokens in Figma using the same names as CSS variables.
- Create 4 key frames first:
  - Auth gateway desktop
  - Chat desktop
  - Analysis desktop
  - Inventory mobile
- Define component variants for:
  - Button
  - Input
  - Card
  - Table row
  - Status banner
  - Drawer
  - Empty state

Handoff requirements:

- Spacing uses token names, not freehand values
- Colors reference semantic tokens, not raw hex in component annotations
- Interactive states must include default / hover / active / focus / disabled

### 6.4 Performance Notes

- Keep blur areas constrained to panel containers; avoid full-screen blur on low-end devices
- Lazy-load non-default pages with dynamic imports
- Use `content-visibility: auto` for long session/history lists where appropriate
- Batch local storage sync and remote sync to avoid chat typing jank
- Avoid box-shadow stacks heavier than `shadow-md`
- Prefer CSS transforms and opacity for motion, never `top/left` animation
- Add `prefers-reduced-motion` fallback for all stagger and panel transitions

### 6.5 Delivery Priority

Recommended order:

1. Build token layer and global shell
2. Redesign `/chat` and `/analysis` together
3. Rebuild auth gateway
4. Upgrade `/inventory` into a dashboard
5. Rework `/document-process`
6. Recast `/workflow`

This order yields the fastest visible improvement because the core shell and the two highest-frequency workspaces define the product's quality perception.

## 7. Copyable Starter Tokens

```css
:root[data-theme="light"] {
  --brand-500: #2563eb;
  --brand-600: #1d4ed8;
  --teal-500: #0f9f8d;
  --amber-500: #d97706;
  --rose-500: #d1435b;
  --bg-canvas: #f5f7fb;
  --bg-surface-1: rgba(255, 255, 255, 0.78);
  --bg-surface-2: #ffffff;
  --bg-muted: #eef2f8;
  --border-subtle: rgba(15, 23, 42, 0.08);
  --border-strong: rgba(15, 23, 42, 0.14);
  --text-primary: #0f172a;
  --text-secondary: #475569;
  --text-tertiary: #64748b;
  --text-inverse: #f8fafc;
  --radius-xs: 4px;
  --radius-sm: 6px;
  --radius-md: 8px;
  --shadow-xs: 0 1px 2px rgba(15, 23, 42, 0.04);
  --shadow-sm: 0 4px 12px rgba(15, 23, 42, 0.06);
  --shadow-md: 0 8px 24px rgba(15, 23, 42, 0.10);
  --ease-standard: cubic-bezier(0.22, 1, 0.36, 1);
  --duration-fast: 180ms;
  --duration-base: 220ms;
  --duration-slow: 260ms;
}

:root[data-theme="dark"] {
  --brand-500: #5b8cff;
  --brand-600: #7aa2ff;
  --teal-500: #2fc6b2;
  --amber-500: #f0a43b;
  --rose-500: #f06a83;
  --bg-canvas: #0b1020;
  --bg-surface-1: rgba(15, 23, 42, 0.72);
  --bg-surface-2: #111827;
  --bg-muted: #182235;
  --border-subtle: rgba(148, 163, 184, 0.16);
  --border-strong: rgba(148, 163, 184, 0.28);
  --text-primary: #e5edf7;
  --text-secondary: #b2c0d4;
  --text-tertiary: #8a9ab3;
  --text-inverse: #08111f;
}
```
