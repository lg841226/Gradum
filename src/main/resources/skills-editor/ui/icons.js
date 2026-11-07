// Inline SVG sprite. Icons are stored as inner markup for a 16x16 viewBox and
// rendered by the Icon component, so no network font or CDN script is needed.
//
// Vendored from the IntelliJ platform, all under Apache 2.0,
// Copyright 2000-2025 JetBrains s.r.o. and contributors:
//   lib/intellij.platform.ide.jar
//       expui/{actions,toolwindows,general,status,run,build,nodes,fileTypes}/*
//   plugins/Kotlin/lib/intellij.kotlin.base.resources.jar
//                                          org/jetbrains/kotlin/idea/icons/expui/kotlin_dark.svg
//
// Monochrome icons have their grey rewritten to currentColor so they follow
// hover + selected states, drawn at the platform stroke width of 1. The
// Kotlin brand mark is a filled sprite, so its two stops read the
// --icon-kotlin-* tokens (via inline fill, since a presentation attribute
// cannot hold var()) and flip with the theme; the coloured status icons keep
// their own palette, and the completion kind marks keep theirs while reading
// --icon-node-* the same way, so they show their dark twin when the theme does.

export const icons = {
  // expui/general/add.svg
  plus: '<path fill-rule="evenodd" clip-rule="evenodd" d="M7.5 1C7.77614 1 8 1.22386 8 1.5V7H13.5'
    + 'C13.7761 7 14 7.22386 14 7.5C14 7.77614 13.7761 8 13.5 8H8V13.5C8 13.7761 7.77614 14 7.5 14'
    + 'C7.22386 14 7 13.7761 7 13.5V8H1.5C1.22386 8 1 7.77614 1 7.5C1 7.22386 1.22386 7 1.5 7H7V1.5'
    + 'C7 1.22386 7.22386 1 7.5 1Z" fill="currentColor" stroke="none"/>',

  // expui/general/chevronDown.svg
  "chevron-down": '<path d="M11.5 6.25L8 9.75L4.5 6.25" stroke-width="1" stroke-linecap="round"/>',

  // expui/general/chevronRight.svg
  "chevron-right": '<path d="M6 11.5L9.5 8L6 4.5" stroke-width="1" stroke-linecap="round"/>',

  // The platform's breadcrumb double chevron, verbatim
  "chevrons": '<path d="M7.5 11.5L12 7L7.5 2.5M2.5 11.5L7 7L2.5 2.5"'
    + ' stroke-width="1" stroke-linecap="round"/>',

  // expui/actions/checked.svg
  check: '<path d="M2.5 8.25L6 11.75L13.5 4.25" stroke-width="1"'
    + ' stroke-linecap="round" stroke-linejoin="round"/>',

  // expui/actions/attach.svg, with attach_dark.svg merged into currentColor
  attach: '<path d="M2.49961 7.5L7.49958 2.50003C8.88031 1.1193 11.1189 1.1193'
    + ' 12.4996 2.50002V2.50002C13.8803 3.88073 13.8804 6.11929 12.4997 7.50002'
    + 'L6.09999 13.9C5.27156 14.7284 3.92839 14.7285 3.09989 13.9001V13.9001'
    + 'C2.27132 13.0716 2.27128 11.7283 3.09979 10.8998L9.49961 4.5"'
    + ' stroke-width="1" stroke-linecap="round"/>',

  // expui/general/scrollDown.svg, the platform's scroll-to-bottom mark
  "scroll-down": '<path d="M1.5 3.5H5.5M1.5 8H5.5M1.5 12.5H14.5" stroke-width="1"'
    + ' stroke-linecap="round" stroke-linejoin="round"/>'
    + '<path fill-rule="evenodd" clip-rule="evenodd" d="M11.5 3C11.7761 3 12 3.22386 12 3.5L12'
    + ' 9.29291L14.1465 7.14646C14.3417 6.95119 14.6583 6.9512 14.8536 7.14646C15.0488 7.34172'
    + ' 15.0488 7.65831 14.8536 7.85357L11.8536 10.8536C11.6583 11.0488 11.3417 11.0488 11.1464'
    + ' 10.8536L8.14648 7.85356C7.95121 7.6583 7.95122 7.34172 8.14648 7.14646C8.34174 6.9512'
    + ' 8.65832 6.9512 8.85358 7.14646L11 9.29289L11 3.5C11 3.22386 11.2239 3 11.5 3Z"'
    + ' fill="currentColor" stroke="none"/>',

  // expui/general/menu.svg, the hamburger of the touch nav
  menu: '<path d="M2 3.5C2 3.22386 2.22386 3 2.5 3H13.5C13.7761 3 14 3.22386 14 3.5C14 3.77614 13.7761 4 13.5 4H2.5C2.22386 4 2 3.77614 2 3.5Z" fill="currentColor" stroke="none"/>'
    + '<path d="M2 7.5C2 7.22386 2.22386 7 2.5 7H13.5C13.7761 7 14 7.22386 14 7.5C14 7.77614 13.7761 8 13.5 8H2.5C2.22386 8 2 7.77614 2 7.5Z" fill="currentColor" stroke="none"/>'
    + '<path d="M2.5 11C2.22386 11 2 11.2239 2 11.5C2 11.7761 2.22386 12 2.5 12H13.5C13.7761 12 14 11.7761 14 11.5C14 11.2239 13.7761 11 13.5 11H2.5Z" fill="currentColor" stroke="none"/>',

  // expui/nodes/plugin.svg, cutout layer dropped for monochrome use
  "nodes-plugin": '<path fill-rule="evenodd" clip-rule="evenodd" d="M11 4H7C5.34315 4 4 5.34315'
    + ' 4 7V9C4 10.6569 5.34315 12 7 12H11V11V10V6V5V4ZM12 5V4C12 3.44772 11.5523 3 11 3H7'
    + 'C5.13616 3 3.57006 4.27477 3.12602 6H1C0.447715 6 0 6.44772 0 7V9C0 9.55228 0.447715'
    + ' 10 1 10H3.12602C3.57006 11.7252 5.13616 13 7 13H11C11.5523 13 12 12.5523 12 12V11H15.5'
    + 'C15.7761 11 16 10.7761 16 10.5C16 10.2239 15.7761 10 15.5 10H12V6H15.5C15.7761 6 16'
    + ' 5.77614 16 5.5C16 5.22386 15.7761 5 15.5 5H12ZM3 7V9H1V7L3 7Z" fill="currentColor" stroke="none"/>',

  // expui/nodes/related.svg
  "nodes-related": '<path d="M11.7072 9.58572C11.5119 9.39046 11.5119 9.07388 11.7072 8.87861L13.0507'
    + ' 7.53511C14.317 6.26878 14.317 4.21565 13.0507 2.94932C11.7843 1.68299 9.73121 1.68299'
    + ' 8.46489 2.94932L7.12138 4.29283C6.92612 4.48809 6.60953 4.48809 6.41427 4.29283C6.21901'
    + ' 4.09757 6.21901 3.78098 6.41427 3.58572L7.75778 2.24221C9.41463 0.585359 12.1009 0.58536'
    + ' 13.7578 2.24221C15.4146 3.89907 15.4146 6.58536 13.7578 8.24221L12.4143 9.58572C12.219'
    + ' 9.78098 11.9024 9.78098 11.7072 9.58572Z" fill="currentColor" stroke="none"/>'
    + '<path d="M10.6464 4.64645C10.8417 4.45118 11.1583 4.45118 11.3536 4.64645C11.5488 4.84171'
    + ' 11.5488 5.15829 11.3536 5.35355L5.35355 11.3536C5.15829 11.5488 4.84171 11.5488 4.64645'
    + ' 11.3536C4.45118 11.1583 4.45118 10.8417 4.64645 10.6464L10.6464 4.64645Z" fill="currentColor" stroke="none"/>'
    + '<path d="M4.29289 6.41421C4.48815 6.60947 4.48815 6.92605 4.29289 7.12132L2.95316 8.46104'
    + ' C1.68684 9.72737 1.68684 11.7805 2.95317 13.0468C4.2195 14.3132 6.27262 14.3132 7.53895'
    + ' 13.0468L8.87871 11.7071C9.07396 11.5118 9.39051 11.5118 9.58578 11.707C9.78107 11.9023'
    + ' 9.78109 12.2189 9.58581 12.4142L8.24606 13.7539C6.5892 15.4108 3.90291 15.4108 2.24606'
    + ' 13.7539C0.589205 12.0971 0.589204 9.41079 2.24606 7.75393L3.58578 6.41421C3.78104 6.21895'
    + ' 4.09763 6.21895 4.29289 6.41421Z" fill="currentColor" stroke="none"/>',

  // expui/general/hide_dark.svg
  hide: '<path fill-rule="evenodd" clip-rule="evenodd" d="M1 7.5C1 7.77614 1.22386 8 1.5 8L13.5 8'
    + 'C13.7761 8 14 7.77614 14 7.5C14 7.22386 13.7761 7 13.5 7L1.5 7C1.22386 7 1 7.22386 1 7.5Z"'
    + ' fill="currentColor" stroke="none"/>',

  // expui/general/closeSmall.svg
  close: '<path fill-rule="evenodd" clip-rule="evenodd" d="M11.4939 4.48784C11.3002 4.28007'
    + ' 10.9724 4.27548 10.7729 4.47775L8.00074 7.28849L5.22871 4.47788C5.02922 4.27561'
    + ' 4.70143 4.2802 4.50768 4.48797C4.32506 4.68382 4.32933 4.98882 4.51736 5.17947'
    + 'L7.29908 7.99991L4.51756 10.8201C4.32953 11.0108 4.32526 11.3158 4.50788 11.5116'
    + 'C4.70163 11.7194 5.02942 11.724 5.22892 11.5217L8.00074 8.71133L10.7727 11.5219'
    + 'C10.9722 11.7241 11.3 11.7196 11.4937 11.5118C11.6764 11.3159 11.6721 11.0109'
    + ' 11.484 10.8203L8.7024 7.99991L11.4843 5.17934C11.6723 4.98869 11.6766 4.68368'
    + ' 11.4939 4.48784Z" fill="currentColor" stroke="none"/>',

  // expui/toolwindows/run.svg, drawn at the platform's own stroke width.
  run: '<path d="M13.5 7.13397C14.1667 7.51888 14.1667 8.48113 13.5 8.86603L4.5 14.0622'
    + 'C3.83333 14.4471 3 13.966 3 13.1962L3 2.80385C3 2.03405 3.83333 1.55292 4.5 1.93782'
    + 'L13.5 7.13397Z" stroke-width="1" stroke-linejoin="miter"/>',

  // expui/build/build.svg
  build: '<path fill-rule="evenodd" clip-rule="evenodd" d="M3.5 1C3.63261 1 3.75975 1.05272'
    + ' 3.85352 1.14648'
    + 'L4.70703 2H5.29297L6.14648 1.14648L6.22266 1.08398C6.30419 1.02963 6.40056 1 6.5 1H10.5'
    + 'C13.3162 1 15 3.2657 15 5.5C15 5.67329 14.9101 5.83468 14.7627 5.92578C14.6153 6.01669'
    + ' 14.4312 6.02471 14.2764 5.94727L12.3818 5H10.707L10 5.70703V14.5C10 14.7761 9.77614 15'
    + ' 9.5 15H6.5C6.22386 15 6 14.7761 6 14.5V5.70703L5.29297 5H4.70703L3.85352 5.85352'
    + 'C3.75975 5.94728 3.63261 6 3.5 6H1.5C1.22386 6 1 5.77614 1 5.5V1.5C1 1.22386 1.22386 1'
    + ' 1.5 1H3.5ZM7 14H9V6H7V14ZM2 5H3.29297L4.14648 4.14648L4.22266 4.08398C4.30419 4.02963'
    + ' 4.40056 4 4.5 4H5.5C5.63261 4 5.75975 4.05272 5.85352 4.14648L6.70703 5H9.29297'
    + 'L10.1465 4.14648L10.2227 4.08398C10.3042 4.02963 10.4006 4 10.5 4H12.5'
    + 'C12.5776 4 12.6542 4.01802'
    + ' 12.7236 4.05273L13.8936 4.6377C13.5415 3.21018 12.324 2 10.5 2H6.70703L5.85352 2.85352'
    + 'C5.75975 2.94728 5.63261 3 5.5 3H4.5C4.36739 3 4.24025 2.94728 4.14648 2.85352'
    + 'L3.29297 2H2V5Z"'
    + ' fill="currentColor" stroke="none"/>',

  // expui/actions/split_dark.svg. Two columns, the left outlined and the right
  // solid, which is the platform's "split right" mark.
  split: '<path fill-rule="evenodd" clip-rule="evenodd" d="M8.5 3H12C12.5523 3 13 3.44772 13 4V12'
    + 'C13 12.5523 12.5523 13 12 13H8.5V3ZM7.5 2H8.5H12C13.1046 2 14 2.89543 14 4V12'
    + 'C14 13.1046 13.1046 14 12 14H8.5H7.5H4C2.89543 14 2 13.1046 2 12V4C2 2.89543'
    + ' 2.89543 2 4 2H7.5ZM7.5 13H4C3.44772 13 3 12.5523 3 12V4C3 3.44772 3.44772 3 4 3H7.5V13Z"'
    + ' fill="currentColor" stroke="none"/>',

  // expui/actions/minimap.svg. Four bars of alternating length: the platform's
  // minimap mark, gray rewritten to currentColor like the rest of the set.
  minimap: '<rect x="2" y="3" width="12" height="1" rx="0.5" fill="currentColor" stroke="none"/>'
    + '<rect x="2" y="6" width="8" height="1" rx="0.5" fill="currentColor" stroke="none"/>'
    + '<rect x="2" y="9" width="12" height="1" rx="0.5" fill="currentColor" stroke="none"/>'
    + '<rect x="2" y="12" width="8" height="1" rx="0.5" fill="currentColor" stroke="none"/>',

  // The same mark turned a quarter turn, so the solid column comes to rest along
  // the bottom: the platform's "split down" reading.
  "split-down": '<g transform="rotate(90 8 8)">'
    + '<path fill-rule="evenodd" clip-rule="evenodd" d="M8.5 3H12'
    + 'C12.5523 3 13 3.44772 13 4V12C13 12.5523 12.5523 13 12 13H8.5V3ZM7.5 2H8.5H12'
    + 'C13.1046 2 14 2.89543 14 4V12C14 13.1046 13.1046 14 12 14H8.5H7.5H4C2.89543 14 2 13.1046'
    + ' 2 12V4C2 2.89543 2.89543 2 4 2H7.5ZM7.5 13H4C3.44772 13 3 12.5523 3 12V4C3 3.44772'
    + ' 3.44772 3 4 3H7.5V13Z" fill="currentColor" stroke="none"/></g>',

  // Kotlin plugin. The dark variant (expui/kotlin_dark.svg) shapes the sprite;
  // the light variant (expui/kotlin.svg) supplies the other token values.
  kotlin: '<path d="M13.3337 12.6314C13.6699 12.9396 13.4519 13.5 12.9958 13.5H3C2.72386 13.5'
    + ' 2.5 13.2761 2.5 13V3C2.5 2.72386 2.72386 2.5 3 2.5H12.9958C13.4519 2.5 13.6699 3.06044'
    + ' 13.3337 3.36858L8.68333 7.63142C8.46715 7.82959 8.46715 8.17041 8.68333 8.36858'
    + 'L13.3337 12.6314Z" stroke="none" style="fill:var(--icon-kotlin-wedge)"/>'
    + '<path fill-rule="evenodd" clip-rule="evenodd" d="M2 3C2 2.44772 2.44771 2 3 2H12.9958'
    + 'C13.9079 2 14.3439 3.12089 13.6716 3.73715L9.0212 8L13.6716 12.2628C14.3439 12.8791'
    + ' 13.9079 14 12.9958 14H3C2.44771 14 2 13.5523 2 13V3ZM12.9958 3L3 3V13H12.9958'
    + 'L8.34547 8.73715C7.91311 8.34082 7.9131 7.65918 8.34547 7.26285L12.9958 3Z"'
    + ' stroke="none" style="fill:var(--icon-kotlin-body)"/>',

  // expui/general/copy.svg + copy_dark.svg, AllIconsKeys.Actions.Copy. The two
  // variants share one 16x16 drawing and differ only in ink, so both greys fold
  // onto currentColor and the frame keeps the platform's 1px stroke width. Apache
  // 2.0, Copyright 2000-2023 JetBrains s.r.o. and contributors.
  copy: '<rect x="2.5" y="3.5" width="9" height="10" rx="1.5" fill="none"'
    + ' stroke="currentColor" stroke-width="1"/>'
    + '<rect x="5" y="6" width="4" height="1" rx="0.5" fill="currentColor" stroke="none"/>'
    + '<rect x="5" y="8" width="4" height="1" rx="0.5" fill="currentColor" stroke="none"/>'
    + '<rect x="5" y="10" width="4" height="1" rx="0.5" fill="currentColor" stroke="none"/>'
    + '<path fill-rule="evenodd" clip-rule="evenodd" d="M11.0017 2H11.5998C12.373 2 12.9998'
    + ' 2.6268 12.9998 3.4V3.91081C13.0011 3.94038 13.0017 3.97011 13.0017 4V11.5482C13.6063'
    + ' 11.1124 13.9998 10.4021 13.9998 9.6V3.4C13.9998 2.07452 12.9253 1 11.5998 1H6.39978'
    + 'C5.59677 1 4.88587 1.39437 4.4502 2H6.39978H11.0017Z" fill="currentColor" stroke="none"/>',

  // expui/run/rerun.svg + rerun_dark.svg, AllIconsKeys.Run.Rerun. The two differ
  // only in the green + ink values, so the arrow and play outline read
  // currentColor and the play's inner fill layer is dropped for monochrome use.
  // Apache 2.0, Copyright 2000-2023 JetBrains s.r.o. and contributors.
  rerun: '<path fill-rule="evenodd" clip-rule="evenodd" d="M9 9.50215C9 8.32441 10.2951'
    + ' 7.60608 11.2942 8.22967L15.2962 10.7275C16.2372 11.3149 16.2372 12.6851 15.2962'
    + ' 13.2725L11.2942 15.7703C10.2951 16.3939 9 15.6756 9 14.4978V9.50215ZM10.7647'
    + ' 9.07799C10.4317 8.87013 10 9.10957 10 9.50215V14.4978C10 14.8904 10.4317 15.1298'
    + ' 10.7647 14.922L14.7667 12.4241C15.0804 12.2284 15.0804 11.7716 14.7667 11.5758L10.7647'
    + ' 9.07799Z" fill="currentColor" stroke="none"/>'
    + '<path d="M14 1.5C14 1.22386 13.7761 1 13.5 1C13.2239 1 13 1.22386 13 1.5V4.68235'
    + 'C11.9256 3.06631 10.0877 2 8 2C4.68629 2 2 4.68629 2 8C2 11.3137 4.68629 14 8 14V13'
    + 'C5.23858 13 3 10.7614 3 8C3 5.23858 5.23858 3 8 3C9.63527 3 11.0878 3.78495 12.0005 5'
    + 'H9.5C9.22386 5 9 5.22386 9 5.5C9 5.77614 9.22386 6 9.5 6H14V1.5Z" fill="currentColor"'
    + ' stroke="none"/>',

  // expui/toolwindows/project.svg, the folder glyph the platform draws for tree
  // roots.
  folder: '<path d="M8.15132 4.35836L8.29689 4.5H8.5H13C13.8284 4.5 14.5 5.17157 14.5 6V12.1333'
    + 'C14.5 12.919 13.9104 13.5 13.25 13.5H2.75C2.08955 13.5 1.5 12.919 1.5 12.1333V3.86667'
    + 'C1.5 3.08099 2.08955 2.5 2.75 2.5H6.03823C6.16847 2.5 6.29357 2.55082 6.38691 2.64164'
    + 'L8.15132 4.35836Z" stroke-width="1" stroke-linejoin="miter"/>',

  // The Gradum chat bubble, copied from the plugin's chat-mode icon
  // (icons/chat-mode/chat-mode.svg): the same glyph GradumIcons.Chat draws
  // for session lists. Its fixed #6C707E stroke becomes currentColor so the
  // tree rows dim + highlight with the theme like every other icon here.
  session: '<path d="M4 2.5H12C12.8284 2.5 13.5 3.17157 13.5 4V13.96L9.3125 10.6094C9.22384'
    + ' 10.5384 9.11354 10.5 9 10.5H4C3.17157 10.5 2.5 9.82843 2.5 9V4C2.5 3.17157 3.17157'
    + ' 2.5 4 2.5Z" stroke-width="1" stroke-linejoin="round"/>',

  // The platform's package node mark (package/package.svg, Apache 2.0,
  // Copyright 2000-2022 JetBrains s.r.o. and contributors). Its #EBECF0
  // backing plate is dropped and the #6C707E ink rewritten to currentColor,
  // following the same rule as cmd below.
  package: '<path fill-rule="evenodd" clip-rule="evenodd" fill="currentColor"'
    + ' stroke="none" d="M8.04022 5L6.1217 3H2.63333C2.40481 3 2 3.25841 2 3.86667'
    + 'V12.1333C2 12.7416 2.40481 13 2.63333 13H13.3667C13.5952 13 14 12.7416 14 12.1333V6'
    + 'C14 5.44771 13.5523 5 13 5H8.04022ZM8.46667 4L6.84336 2.30775C6.65477 2.11115'
    + ' 6.39413 2 6.1217 2H2.63333C1.73127 2 1 2.83574 1 3.86667V12.1333C1 13.1643'
    + ' 1.73127 14 2.63333 14H13.3667C14.2687 14 15 13.1643 15 12.1333V6C15 4.89543'
    + ' 14.1046 4 13 4H8.46667ZM4 9C4 7.89543 4.89543 7 6 7C7.10457 7 8 7.89543 8 9'
    + 'C8 10.1046 7.10457 11 6 11C4.89543 11 4 10.1046 4 9ZM7 9C7 9.55228 6.55228 10'
    + ' 6 10C5.44772 10 5 9.55228 5 9C5 8.44772 5.44772 8 6 8C6.55228 8 7 8.44772 7 9Z"/>',

  // expui/nodes/module.svg + module_dark.svg. The two are the same 16x16
  // drawing and differ only in ink, so the light/dark values fold onto theme
  // tokens: the badge plate reads --accent-selection, its ring --accent, the
  // folder body --bg-raised, and the outline currentColor, which keeps the
  // glyph visible on both themes. Apache 2.0, Copyright 2000-2022 JetBrains
  // s.r.o. and contributors.
  module: '<rect x="9.5" y="9.5" width="6" height="6" rx="1.5" stroke-width="1"'
    + ' style="fill:var(--accent-selection);stroke:var(--accent)"/>'
    + '<path fill-rule="evenodd" clip-rule="evenodd" d="M1 3.86667C1 2.83574 1.7835 2 2.75 2H6.03823'
    + 'C6.29871 2 6.5489 2.10163 6.73559 2.28327L8.5 4L13 4C14.1046 4 15 4.89543 15 6V8.17071'
    + 'C14.6872 8.06015 14.3506 8 14 8H11C9.34315 8 8 9.34315 8 11V14H2.75C1.7835 14 1 13.1643'
    + ' 1 12.1333V3.86667Z" style="fill:var(--bg-raised)" stroke="none"/>'
    + '<path fill-rule="evenodd" clip-rule="evenodd" d="M7.80265 4.71673L8.09379 5H8.5H13'
    + 'C13.5523 5 14 5.44772 14 6V8C14.3506 8 14.6872 8.06015 15 8.17071V6C15 4.89543 14.1046 4'
    + ' 13 4L8.5 4L6.73559 2.28327C6.5489 2.10163 6.29871 2 6.03823 2H2.75C1.7835 2 1 2.83574'
    + ' 1 3.86667V12.1333C1 13.1643 1.7835 14 2.75 14H8V13H2.75C2.3956 13 2 12.6738 2 12.1333'
    + 'V3.86667C2 3.32624 2.3956 3 2.75 3H6.03823L7.80265 4.71673Z" fill="currentColor" stroke="none"/>',

  // expui CSS file mark, css.svg + css_dark.svg. Both are the same 16x16
  // drawing and differ only in the plate's blue, which folds onto --accent;
  // the lettering keeps the source's white. Apache 2.0, Copyright 2000-2024
  // JetBrains s.r.o. and contributors.
  css: '<path d="M7.99237 15L2.94069 13.6005L1.81494 1H14.1849L13.058 13.5985L7.99237 15Z"'
    + ' style="fill:var(--accent)" stroke="none"/>'
    + '<path d="M4.25747 5.1211L4.1167 3.57568H11.8739L11.5943 6.70361L11.5565 7.1186'
    + 'L11.1693 11.4482L7.99978 12.3249L7.99266 12.327L4.82048 11.4482L4.60352 9.02173H6.15807'
    + 'L6.26832 10.2543L7.993 10.719L7.99463 10.7186L9.7218 10.2533L9.90155 8.249L4.53431 8.249'
    + 'L4.39551 6.70361H10.0358L10.1768 5.1211H4.25747Z" fill="white" stroke="none"/>',

  // expui/fileTypes/html.svg + html_dark.svg. Both variants draw the same
  // brackets and differ only in green, which folds onto --ok. Apache 2.0,
  // Copyright 2000-2023 JetBrains s.r.o. and contributors.
  html: '<path d="M6.36812 4.9736C6.62969 4.77016 6.67681 4.39319 6.47337 4.13163'
    + 'C6.26992 3.87006 5.89296 3.82294 5.63139 4.02638L0.522461 7.99999'
    + 'L5.63139 11.9736C5.89296 12.177 6.26992 12.1299 6.47337 11.8684'
    + 'C6.67681 11.6068 6.62969 11.2298 6.36812 11.0264L2.47705 7.99999'
    + 'L6.36812 4.9736Z" style="fill:var(--ok)" stroke="none"/>'
    + '<path d="M9.63139 4.9736C9.36983 4.77016 9.3227 4.39319 9.52615 4.13163'
    + 'C9.72959 3.87006 10.1066 3.82294 10.3681 4.02638L15.4771 7.99999'
    + 'L10.3681 11.9736C10.1066 12.177 9.72959 12.1299 9.52615 11.8684'
    + 'C9.3227 11.6068 9.36983 11.2298 9.63139 11.0264L13.5225 7.99999'
    + 'L9.63139 4.9736Z" style="fill:var(--ok)" stroke="none"/>',

  // expui/fileTypes/java.svg + java_dark.svg. The two variants share the ink
  // pair, which folds onto --icon-node-word-bg / --icon-node-word-ink; the
  // rounded frame is kept from the light drawing. Apache 2.0,
  // Copyright 2000-2023 JetBrains s.r.o. and contributors.
  java: '<rect x="2" y="13" width="12" height="1" rx="0.5"'
    + ' style="fill:var(--icon-node-word-ink)" stroke="none"/>'
    + '<path d="M2.5 3.6C2.5 3.31172 2.50039 3.12559 2.51196 2.98392'
    + 'C2.52306 2.84809 2.5419 2.79773 2.5545 2.773C2.60243 2.67892 2.67892 2.60243'
    + ' 2.773 2.5545C2.79773 2.5419 2.84809 2.52306 2.98392 2.51196'
    + 'C3.12559 2.50039 3.31172 2.5 3.6 2.5H11.4C11.6883 2.5 11.8744 2.50039'
    + ' 12.0161 2.51196C12.1519 2.52306 12.2023 2.5419 12.227 2.5545'
    + 'C12.3211 2.60243 12.3976 2.67892 12.4455 2.773L12.8864 2.54834'
    + 'L12.4455 2.773C12.4581 2.79773 12.4769 2.84809 12.488 2.98392'
    + 'C12.4996 3.12559 12.5 3.31172 12.5 3.6V6.5C12.5 7.93194 12.4946 8.53707'
    + ' 12.3493 9.016C12.01 10.1346 11.1346 11.01 10.016 11.3493'
    + 'C9.53707 11.4946 8.93194 11.5 7.5 11.5C6.06806 11.5 5.46293 11.4946'
    + ' 4.984 11.3493C3.8654 11.01 2.99003 10.1346 2.65071 9.016'
    + 'C2.50543 8.53707 2.5 7.93194 2.5 6.5V3.6Z"'
    + ' style="fill:var(--icon-node-word-bg);stroke:var(--icon-node-word-ink)"'
    + ' stroke-width="1"/>'
    + '<path d="M12.5 2.5H14.5C14.7761 2.5 15 2.72386 15 3V4.18121'
    + 'C15 4.78125 14.6424 5.32356 14.0909 5.55993L12.5 6.24173V2.5Z"'
    + ' style="stroke:var(--icon-node-word-ink)" stroke-width="1"/>',

  // expui/fileTypes/javaScript.svg + javaScript_dark.svg. The two plates fold
  // onto --warning and the lettering onto --bg-canvas, so the mark reads as a
  // cut-out on both themes. Apache 2.0, Copyright 2000-2023 JetBrains s.r.o.
  // and contributors.
  javascript: '<path d="M14 4C14 2.89543 13.1046 2 12 2H4C2.89543 2 2 2.89543 2 4V12'
    + 'C2 13.1046 2.89543 14 4 14H12C13.1046 14 14 13.1046 14 12V4Z"'
    + ' style="fill:var(--warning)" stroke="none"/>'
    + '<path d="M9.55434 11.8593C9.88209 12.0132 10.2583 12.0901 10.6829 12.0901'
    + 'C11.1047 12.0901 11.4795 12.0117 11.8073 11.855C12.135 11.6982'
    + ' 12.3901 11.4831 12.5725 11.2095C12.7549 10.933 12.8461 10.6238'
    + ' 12.8461 10.2818C12.8461 9.99963 12.7834 9.73315 12.658 9.48235'
    + 'C12.5326 9.23155 12.3545 9.02208 12.1236 8.85393C11.8956 8.68578'
    + ' 11.6348 8.57605 11.3413 8.52475L10.3452 8.3623C10.1429 8.3281'
    + ' 9.97899 8.24118 9.85359 8.10153C9.72819 7.95903 9.66549 7.79088'
    + ' 9.66549 7.59708C9.66549 7.43178 9.70824 7.28643 9.79374 7.16103'
    + 'C9.87924 7.03563 9.99894 6.93873 10.1528 6.87033C10.3067 6.80193'
    + ' 10.4849 6.76773 10.6872 6.76773C10.8896 6.76773 11.0677 6.80335'
    + ' 11.2216 6.8746C11.3755 6.94585 11.4952 7.04703 11.5807 7.17813'
    + 'C11.6662 7.30638 11.7089 7.45173 11.7089 7.61418H12.7221'
    + 'C12.7193 7.28073 12.6309 6.98433 12.4571 6.72498C12.2832 6.46563'
    + ' 12.041 6.26328 11.7303 6.11793C11.4225 5.97258 11.0705 5.8999'
    + ' 10.6744 5.8999C10.2868 5.8999 9.93909 5.97543 9.63129 6.12648'
    + 'C9.32349 6.27468 9.08266 6.4813 8.90881 6.74635C8.73781 7.00855'
    + ' 8.65231 7.30495 8.65231 7.63555C8.65231 7.912 8.70931 8.16708'
    + ' 8.82331 8.40078C8.94016 8.63163 9.10546 8.824 9.31921 8.9779'
    + 'C9.53296 9.1318 9.78519 9.2344 10.0759 9.2857L11.0891 9.4567'
    + 'C11.3114 9.49375 11.4895 9.5935 11.6234 9.75595C11.7574 9.91555'
    + ' 11.8244 10.1065 11.8244 10.3288C11.8244 10.5055 11.7773 10.6623'
    + ' 11.6833 10.7991C11.5921 10.933 11.461 11.037 11.29 11.1111'
    + 'C11.119 11.1852 10.9209 11.2223 10.6958 11.2223C10.4649 11.2223'
    + ' 10.2597 11.1824 10.0802 11.1026C9.90346 11.0199 9.76524 10.9045'
    + ' 9.66549 10.7563C9.56574 10.6081 9.51586 10.44 9.51586 10.2519'
    + 'H8.50269C8.50554 10.611 8.59959 10.9302 8.78484 11.2095'
    + 'C8.97009 11.4859 9.22659 11.7025 9.55434 11.8593Z"'
    + ' style="fill:var(--bg-canvas)" stroke="none"/>'
    + '<path d="M5.22179 11H4.21289V12H5.32012C5.64787 12 5.93857 11.9316'
    + ' 6.19222 11.7948C6.44587 11.6551 6.64394 11.4613 6.78644 11.2134'
    + 'C6.92894 10.9654 7.00019 10.6804 7.00019 10.3584L7.00024 6H5.99989'
    + 'L5.99984 10.222C5.99984 10.3759 5.96707 10.5127 5.90152 10.6324'
    + 'C5.83882 10.7492 5.74762 10.8404 5.62792 10.906C5.51107 10.9687'
    + ' 5.37569 11 5.22179 11Z" style="fill:var(--bg-canvas)" stroke="none"/>',

  // expui/fileTypes/json.svg + json_dark.svg. Both braces fold onto the purple
  // token the platform already uses for the same family. Apache 2.0,
  // Copyright 2000-2023 JetBrains s.r.o. and contributors.
  json: '<path d="M3.98755 9.77751C3.99755 9.39251 3.87005 9.08501 3.60505 8.85501'
    + 'C3.34005 8.62501 2.98255 8.51001 2.53255 8.51001H2V7.43001H2.53255'
    + 'C2.98255 7.43001 3.34005 7.31501 3.60505 7.08501C3.87005 6.85501'
    + ' 3.99755 6.54751 3.98755 6.16251L3.95755 4.58C3.94755 4.06 4.05505 3.605'
    + ' 4.28005 3.215C4.51005 2.825 4.83755 2.525 5.26255 2.315'
    + 'C5.68755 2.105 6.18755 2 6.76255 2H7.5V3.005H6.77005C6.25005 3.005'
    + ' 5.83755 3.15 5.53255 3.44C5.23255 3.725 5.08505 4.1125 5.09005 4.6025'
    + 'L5.12005 6.17001C5.13005 6.68001 4.99255 7.09751 4.70755 7.42251'
    + 'C4.42255 7.74251 4.03755 7.92001 3.55255 7.95501C4.03755 8.04501'
    + ' 4.42255 8.25501 4.70755 8.58501C4.99255 8.91001 5.13005 9.30501'
    + ' 5.12005 9.77001L5.09005 11.5475C5.08005 11.9875 5.21255 12.3375'
    + ' 5.48755 12.5975C5.76755 12.8625 6.14505 12.995 6.62005 12.995H7.5V14'
    + 'H6.61255C6.07755 14 5.60755 13.9 5.20255 13.7C4.80255 13.505'
    + ' 4.49255 13.2225 4.27255 12.8525C4.05755 12.4875 3.95255 12.06'
    + ' 3.95755 11.57L3.98755 9.77751Z"'
    + ' style="fill:var(--icon-kotlin-body)" stroke="none"/>'
    + '<path d="M12.0145 9.77751C12.0045 9.39251 12.132 9.08501 12.397 8.85501'
    + 'C12.662 8.62501 13.0195 8.51001 13.4695 8.51001H14V7.43001H13.4695'
    + 'C13.0195 7.43001 12.662 7.31501 12.397 7.08501C12.132 6.85501'
    + ' 12.0045 6.54751 12.0145 6.16251L12.0445 4.58C12.0545 4.06 11.9445 3.605'
    + ' 11.7145 3.215C11.4895 2.825 11.1645 2.525 10.7395 2.315'
    + 'C10.3145 2.105 9.81455 2 9.23955 2H8.5V3.005H9.23205C9.75205 3.005'
    + ' 10.162 3.15 10.462 3.44C10.767 3.725 10.917 4.1125 10.912 4.6025'
    + 'L10.882 6.17001C10.872 6.68001 11.0095 7.09751 11.2945 7.42251'
    + 'C11.5795 7.74251 11.9645 7.92001 12.4495 7.95501C11.9645 8.04501'
    + ' 11.5795 8.25501 11.2945 8.58501C11.0095 8.91001 10.872 9.30501'
    + ' 10.882 9.77001L10.912 11.5475C10.922 11.9875 10.787 12.3375'
    + ' 10.507 12.5975C10.232 12.8625 9.85705 12.995 9.38205 12.995H8.5V14'
    + 'H9.38955C9.92455 14 10.392 13.9 10.792 13.7C11.197 13.505'
    + ' 11.507 13.2225 11.722 12.8525C11.942 12.4875 12.0495 12.06'
    + ' 12.0445 11.57L12.0145 9.77751Z"'
    + ' style="fill:var(--icon-kotlin-body)" stroke="none"/>',

  // expui/fileTypes/markdown.svg + markdown_dark.svg. Both blue variants fold
  // onto --accent. Apache 2.0, Copyright 2000-2022 JetBrains s.r.o. and
  // contributors.
  markdown: '<path fill-rule="evenodd" clip-rule="evenodd"'
    + ' d="M12.5929 9.9438L12.5929 4.70001L13.7929 4.70002L13.7929 9.94379'
    + 'L15.0763 8.66037L15.9248 9.5089L13.1929 12.2409L10.4609 9.5089'
    + 'L11.3095 8.66037L12.5929 9.9438Z" style="fill:var(--accent)" stroke="none"/>'
    + '<path d="M0.5 4.70001H2.94558L4.65385 9.14463L4.76288 9.60155'
    + 'L4.85635 9.14463L6.51269 4.70001H8.98423V11.9692H7.14096V7.59732'
    + 'L7.17212 7.12482L5.34442 11.9692H4.08269L2.31212 7.17155'
    + 'L2.34327 7.59732V11.9692H0.5V4.70001Z"'
    + ' style="fill:var(--accent)" stroke="none"/>',

  // expui/fileTypes/image.svg + image_dark.svg. The plate and ink fold onto
  // --icon-node-type-bg / --icon-node-type-ink; strokes keep the platform's
  // width of 1. Apache 2.0, Copyright 2000-2023 JetBrains s.r.o. and
  // contributors.
  image: '<rect x="2.5" y="2.5" width="11" height="11" rx="1.5"'
    + ' style="fill:var(--icon-node-type-bg);stroke:var(--icon-node-type-ink)"'
    + ' stroke-width="1"/>'
    + '<path d="M2.5 9.33566L4.1822 7.66899C4.56052 7.29415 5.16625 7.28159'
    + ' 5.55979 7.64043L11.9861 13.5"'
    + ' style="stroke:var(--icon-node-type-ink)" stroke-width="1"/>'
    + '<circle cx="10" cy="6" r="1.5"'
    + ' style="stroke:var(--icon-node-type-ink)" stroke-width="1"/>',

  // expui/fileTypes/text.svg, with text_dark.svg merged into currentColor.
  // Apache 2.0, Copyright 2000-2022 JetBrains s.r.o. and contributors.
  file: '<rect x="2" y="3" width="12" height="1" rx="0.5" fill="currentColor" stroke="none"/>'
    + '<rect x="2" y="6" width="8" height="1" rx="0.5" fill="currentColor" stroke="none"/>'
    + '<rect x="2" y="9" width="12" height="1" rx="0.5" fill="currentColor" stroke="none"/>'
    + '<rect x="2" y="12" width="8" height="1" rx="0.5" fill="currentColor" stroke="none"/>',

  // The plugin's send mark (icons/send/send_dark.svg), its #CED0D6 stroke
  // rewritten to currentColor like the rest of the set.
  send: '<path d="M9.5 8H3.5L2.5 14.5L14.5 8L2.5 1.5L3.192 6" stroke-width="1"'
    + ' stroke-linecap="round" stroke-linejoin="round"/>',

  // The platform's run-halt mark: expui/run/stop.svg, the icon the plugin's
  // chat toolbar shows via AllIconsKeys.Run.Stop. Its red stroke reads as
  // currentColor here; the pale fill is dropped so the glyph stays an
  // outlined rounded square, same geometry on the 16px grid.
  stop: '<rect x="2.5" y="2.5" width="11" height="11" rx="1.5"'
    + ' stroke-width="1"/>',

  // expui/general/edit.svg: AllIconsKeys.Actions.Edit, the plugin's edit
  // permission mark, its #6C707E stroke rewritten to currentColor.
  edit: '<path d="M11.5973 7.65471L13.6882 5.56049C14.1053 5.15406 14.1003 4.49602'
    + ' 13.6948 4.08627L12.0267 2.3136L12.0224 2.30932C11.6123 1.90004 10.942 1.89327'
    + ' 10.5331 2.31079L8.3867 4.44406M11.5973 7.65471L8.3867 4.44406M11.5973 7.65471'
    + 'L5.74041 13.5H2.50036L2.5 10.32L8.3867 4.44406" stroke-width="1"'
    + ' stroke-miterlimit="10"/>',

  // expui/general/readerMode.svg: AllIconsKeys.General.ReaderMode, the
  // read-only permission mark; its #6C707E fill rewritten to currentColor.
  "reader-mode": '<path fill-rule="evenodd" clip-rule="evenodd" fill="currentColor"'
    + ' stroke="none" d="M7.5 3.33737C7.68355 3.19041 7.93511 3.00584 8.24275 2.82125'
    + "C8.91206 2.41967 9.87706 2 11 2C12.1229 2 13.0879 2.41967 13.7572 2.82125"
    + "C14.0944 3.02357 14.3643 3.22587 14.551 3.37865C14.6446 3.4552 14.7178 3.51976"
    + " 14.7688 3.5663C14.7965 3.59158 14.8239 3.61721 14.8506 3.6435C14.9449 3.73702"
    + " 15 3.86703 15 4V13.5C15 13.7022 14.8782 13.8845 14.6913 13.9619C14.5048 14.0392"
    + " 14.2902 13.9967 14.1472 13.8543L14.1464 13.8536L14.1369 13.8443C14.1281 13.8359"
    + " 14.1139 13.8225 14.0945 13.8048C14.0556 13.7693 13.9961 13.7167 13.9178 13.6526"
    + "C13.7607 13.5241 13.5306 13.3514 13.2428 13.1787C12.6621 12.8303 11.8771 12.5 11 12.5"
    + "C10.1229 12.5 9.33794 12.8303 8.75725 13.1787C8.46943 13.3514 8.23926 13.5241 8.08224"
    + " 13.6526C8.00393 13.7167 7.94436 13.7693 7.90549 13.8048C7.88815 13.8206 7.87102"
    + " 13.8367 7.85414 13.853C7.83701 13.8702 7.81855 13.8862 7.79892 13.9008C7.78588"
    + " 13.9106 7.77231 13.9197 7.75825 13.9282C7.62428 14.009 7.45751 14.0236 7.30866"
    + " 13.9619C7.24287 13.9347 7.18772 13.8934 7.13688 13.8443C7.12813 13.8359 7.11393"
    + " 13.8225 7.09451 13.8048C7.05564 13.7693 6.99607 13.7167 6.91776 13.6526C6.76074"
    + " 13.5241 6.53057 13.3514 6.24275 13.1787C5.66206 12.8303 4.87706 12.5 4 12.5"
    + "C3.12294 12.5 2.33794 12.8303 1.75725 13.1787C1.46943 13.3514 1.23926 13.5241"
    + " 1.08224 13.6526C1.00393 13.7167 0.944363 13.7693 0.905494 13.8048C0.886075"
    + " 13.8225 0.871868 13.8359 0.86312 13.8443L0.854136 13.853L0.85334 13.8538"
    + "C0.710341 13.9966 0.495403 14.0393 0.308658 13.9619C0.121821 13.8845 0 13.7022"
    + ' 0 13.5V4C0 3.86754 0.0539197 3.73872 0.147648 3.64525L0.171548 3.622C0.185749'
    + " 3.6084 0.205722 3.58959 0.231225 3.5663C0.2822 3.51976 0.355446 3.4552 0.449006"
    + " 3.37865C0.635738 3.22587 0.905566 3.02357 1.24275 2.82125C1.91206 2.41967 2.87706"
    + " 2 4 2C5.12294 2 6.08794 2.41967 6.75725 2.82125C7.06489 3.00584 7.31645 3.19041"
    + ' 7.5 3.33737ZM8 12.474C8.07644 12.4236 8.15744 12.3724 8.24275 12.3213C8.91206'
    + " 11.9197 9.87706 11.5 11 11.5C12.1229 11.5 13.0879 11.9197 13.7572 12.3213"
    + "C13.8426 12.3724 13.9236 12.4236 14 12.474V4.2214C13.9755 4.20039 13.948 4.17735"
    + " 13.9178 4.1526C13.7607 4.02413 13.5306 3.85143 13.2428 3.67875C12.6621 3.33033"
    + " 11.8771 3 11 3C10.1229 3 9.33794 3.33033 8.75725 3.67875C8.46943 3.85143 8.23926"
    + " 4.02413 8.08224 4.1526C8.05199 4.17735 8.02454 4.20039 8 4.2214V12.474ZM1 4.2214"
    + "C1.02454 4.20039 1.05199 4.17735 1.08224 4.1526C1.23926 4.02413 1.46943 3.85143"
    + " 1.75725 3.67875C2.33794 3.33033 3.12294 3 4 3C4.87706 3 5.66206 3.33033 6.24275"
    + " 3.67875C6.53057 3.85143 6.76074 4.02413 6.91776 4.1526C6.94801 4.17735 6.97546"
    + " 4.20039 7 4.2214V12.474C6.92356 12.4236 6.84256 12.3724 6.75725 12.3213C6.08794"
    + " 11.9197 5.12294 11.5 4 11.5C2.87706 11.5 1.91206 11.9197 1.24275 12.3213C1.15744"
    + ' 12.3724 1.07644 12.4236 1 12.474V4.2214Z"/>',

  // expui/status/error_dark.svg
  error: '<circle cx="8" cy="8" r="7" fill="#DB5C5C" stroke="none"/>'
    + '<path d="M9 5C9 4.44772 8.55228 4 8 4C7.44772 4 7 4.44772 7 5V7.5C7 8.05229 7.44772 8.5'
    + ' 8 8.5C8.55229 8.5 9 8.05228 9 7.5L9 5Z" fill="#FFFFFF" stroke="none"/>'
    + '<path d="M8 12C8.55228 12 9 11.5523 9 11C9 10.4477 8.55228 10 8 10C7.44772 10 7 10.4477'
    + ' 7 11C7 11.5523 7.44772 12 8 12Z" fill="#FFFFFF" stroke="none"/>',

  // expui/status/warning_dark.svg
  warning: '<path fill-rule="evenodd" clip-rule="evenodd" d="M1.27603 10.8634L6.3028 1.98903'
    + 'C7.04977 0.670323 8.94893 0.670326 9.69589 1.98903L14.7227 10.8634C15.516 12.2639'
    + ' 14.5047 14 12.8956 14H3.10308C1.494 14 0.482737 12.2639 1.27603 10.8634Z"'
    + ' fill="#F2C55C" stroke="none"/>'
    + '<path d="M9 5C9 4.44772 8.55228 4 8 4C7.44772 4 7 4.44772 7 5V7.5C7 8.05229 7.44772 8.5'
    + ' 8 8.5C8.55229 8.5 9 8.05228 9 7.5L9 5Z" fill="#5E4D33" stroke="none"/>'
    + '<path d="M8 12C8.55228 12 9 11.5523 9 11C9 10.4477 8.55228 10 8 10C7.44772 10 7 10.4477'
    + ' 7 11C7 11.5523 7.44772 12 8 12Z" fill="#5E4D33" stroke="none"/>',

  // expui/status/errorOutline.svg and warningOutline.svg, the hollow severity
  // marks the status bar counts with. The platform grays them per theme; the
  // gray is rewritten to currentColor so each count can take its severity token.
  "warning-outline": '<path fill-rule="evenodd" clip-rule="evenodd" d="M7.17283 2.48224'
    + 'L2.14605 11.3566'
    + 'C1.73052 12.0902 2.26023 12.9996 3.10308 12.9996H12.8956C13.7385 12.9996 14.2682 12.0902'
    + ' 13.8526 11.3566L8.82587 2.48224C8.46196 1.83979 7.53673 1.8398'
    + ' 7.17283 2.48224ZM1.27603 10.8634'
    + 'L6.3028 1.98903C7.04977 0.670323 8.94893 0.670326 9.69589 1.98903L14.7227 10.8634'
    + 'C15.516 12.2639 14.5047 14 12.8956 14H3.10308C1.494 14 0.482737 12.2639 1.27603 10.8634Z"'
    + ' fill="currentColor" stroke="none"/>'
    + '<path d="M9 5C9 4.44772 8.55228 4 8 4C7.44772 4 7 4.44772 7 5V8C7 8.55229 7.44772 9 8 9'
    + 'C8.55229 9 9 8.55228 9 8L9 5Z" fill="currentColor" stroke="none"/>'
    + '<path d="M8 12C8.55228 12 9 11.5523 9 11C9 10.4477 8.55228 10 8 10C7.44772 10 7 10.4477'
    + ' 7 11C7 11.5523 7.44772 12 8 12Z" fill="currentColor" stroke="none"/>',

  "error-outline": '<circle cx="8" cy="8" r="6.5" stroke-width="1"/>'
    + '<path d="M9 5C9 4.44772 8.55228 4 8 4C7.44772 4 7 4.44772 7 5V8C7 8.55229 7.44772 9 8 9'
    + 'C8.55229 9 9 8.55228 9 8L9 5Z" fill="currentColor" stroke="none"/>'
    + '<path d="M8 12C8.55228 12 9 11.5523 9 11C9 10.4477 8.55228 10 8 10C7.44772 10 7 10.4477'
    + ' 7 11C7 11.5523 7.44772 12 8 12Z" fill="currentColor" stroke="none"/>',

  // expui/general/settings.svg, AllIconsKeys.General.Settings. The platform's
  // #6C707E fill is rewritten to currentColor and the inherited stroke is
  // switched off, because this mark is a filled cutout rather than an outline.
  settings: '<path fill-rule="evenodd" clip-rule="evenodd" d="M3.22655 4.36961C2.92233 4.76924'
    + ' 2.66735 5.20781 2.47037 5.67626L3.30992 7.0943C3.6406 7.65283 3.6406 8.34718'
    + ' 3.30992 8.9057L2.47037 10.3237C2.66736 10.7922 2.92233 11.2308 3.22656 11.6304'
    + 'L4.87261 11.6124C5.52165 11.6053 6.12297 11.9524 6.44133 12.5181L7.24899 13.953'
    + 'C7.49593 13.984 7.74792 14 8.00408 14C8.2602 14 8.51214 13.984 8.75904 13.9531'
    + 'L9.56671 12.5181C9.88507 11.9524 10.4864 11.6053 11.1354 11.6124L12.7816 11.6304'
    + 'C13.0858 11.2308 13.3408 10.7923 13.5377 10.3239L12.6981 8.9057C12.3674 8.34718'
    + ' 12.3674 7.65283 12.6981 7.0943L13.5377 5.67613C13.3408 5.20773 13.0858 4.76921'
    + ' 12.7816 4.36961L11.1354 4.38764C10.4864 4.39475 9.88507 4.04758 9.56671 3.48194'
    + 'L8.75904 2.04693C8.51214 2.01599 8.2602 2 8.00408 2C7.74792 2 7.49594 2.016'
    + ' 7.24899 2.04695L6.44133 3.48194C6.12297 4.04758 5.52165 4.39475 4.87261 4.38764'
    + 'L3.22655 4.36961ZM10.655 8.00001C10.655 9.46412 9.46811 10.651 8.004 10.651'
    + 'C6.5399 10.651 5.353 9.46412 5.353 8.00001C5.353 6.53591 6.5399 5.34902'
    + ' 8.004 5.34902C9.46811 5.34902 10.655 6.53591 10.655 8.00001ZM4.88356 3.3877'
    + 'C5.16752 3.39081 5.4306 3.23892 5.56988 2.99146L6.43817 1.44875C6.54914 1.25159'
    + ' 6.7401 1.1101 6.96387 1.07676C7.30327 1.0262 7.65062 1 8.00408 1C8.3575 1'
    + ' 8.7048 1.02619 9.04414 1.07674C9.26792 1.11007 9.45889 1.25157 9.56986 1.44873'
    + 'L10.4382 2.99146C10.5774 3.23892 10.8405 3.39081 11.1245 3.3877L12.8938 3.36832'
    + 'C13.1196 3.36585 13.3372 3.46012 13.4781 3.63661C13.9099 4.17766 14.2632 4.78416'
    + ' 14.5207 5.43884C14.6034 5.6491 14.5763 5.88489 14.4612 6.07931L13.5586 7.60376'
    + 'C13.4139 7.84811 13.4139 8.15189 13.5586 8.39625L14.4612 9.92069C14.5763 10.1151'
    + ' 14.6034 10.3509 14.5207 10.5612C14.2632 11.2158 13.9099 11.8223 13.4781 12.3634'
    + 'C13.3372 12.5399 13.1196 12.6342 12.8938 12.6317L11.1245 12.6123C10.8405 12.6092'
    + ' 10.5774 12.7611 10.4382 13.0085L9.56986 14.5513C9.45889 14.7484 9.26792 14.8899'
    + ' 9.04414 14.9233C8.7048 14.9738 8.3575 15 8.00408 15C7.65062 15 7.30327 14.9738'
    + ' 6.96387 14.9232C6.7401 14.8899 6.54914 14.7484 6.43817 14.5512L5.56988 13.0085'
    + 'C5.4306 12.7611 5.16752 12.6092 4.88356 12.6123L3.1144 12.6317C2.8886 12.6342'
    + ' 2.67096 12.5399 2.5301 12.3634C2.09822 11.8223 1.74489 11.2158 1.48738 10.561'
    + 'C1.40469 10.3508 1.43184 10.115 1.54695 9.92057L2.44942 8.39625C2.5941 8.15189'
    + ' 2.5941 7.84811 2.44942 7.60376L1.54695 6.07944C1.43184 5.88502 1.40469 5.64924'
    + ' 1.48738 5.43898C1.74489 4.78425 2.09822 4.1777 2.53009 3.63661C2.67096 3.46012'
    + ' 2.8886 3.36585 3.1144 3.36832L4.88356 3.3877ZM9.655 8.00001C9.655 8.91183'
    + ' 8.91582 9.65101 8.004 9.65101C7.09218 9.65101 6.353 8.91183 6.353 8.00001'
    + 'C6.353 7.08819 7.09218 6.34902 8.004 6.34902C8.91582 6.34902 9.655 7.08819'
    + ' 9.655 8.00001Z" fill="currentColor" stroke="none"/>',

  // Theme toggle: a sun for Light, a crescent for Dark. Drawn to the same 16x16
  // grid as the rest of the set.
  "theme-light": '<circle cx="8" cy="8" r="3" stroke-width="1.2"/>'
    + '<path d="M8 1.2V2.6" stroke-width="1.2"/>'
    + '<path d="M8 13.4V14.8" stroke-width="1.2"/>'
    + '<path d="M1.2 8H2.6" stroke-width="1.2"/>'
    + '<path d="M13.4 8H14.8" stroke-width="1.2"/>'
    + '<path d="M3.3 3.3L4.3 4.3" stroke-width="1.2"/>'
    + '<path d="M11.7 11.7L12.7 12.7" stroke-width="1.2"/>'
    + '<path d="M12.7 3.3L11.7 4.3" stroke-width="1.2"/>'
    + '<path d="M4.3 11.7L3.3 12.7" stroke-width="1.2"/>',

  "theme-dark": '<path d="M14 8.53A6 6 0 1 1 7.47 2A4.67 4.67 0 0 0 14 8.53Z" stroke-width="1.2"'
    + ' stroke-linejoin="round"/>',

  // Theme toggle: System, a disc split down the middle (expui/general/systemTheme.svg).
  "theme-system": '<path d="M1.5 8C1.5 11.5899 4.41015 14.5 8 14.5V1.5C4.41015 1.5 1.5 4.41015 1.5 8Z"'
    + ' fill="currentColor"/>'
    + '<path d="M8 2V14" stroke-width="1"/>'
    + '<circle cx="8" cy="8" r="6.5" stroke-width="1"/>',

  // expui/general/locked.svg / locked_dark.svg
  locked: '<path fill-rule="evenodd" clip-rule="evenodd"'
    + ' d="M5 5C5 3.34315 6.34315 2 8 2C9.65685 2 11 3.34315 11 5V6C12.1046 6 13 6.89543 13 8V12C13'
    + ' 13.1046 12.1046 14 11 14H5C3.89543 14 3 13.1046 3 12V8C3 6.89543 3.89543 6 5 6V5ZM10'
    + ' 5V6H6V5C6 3.89543 6.89543 3 8 3C9.10457 3 10 3.89543 10 5ZM5 7C4.44772 7 4 7.44772 4'
    + ' 8V12C4 12.5523 4.44772 13 5 13H11C11.5523 13 12 12.5523 12 12V8C12 7.44772 11.5523 7 11'
    + ' 7H5ZM8 8.5C7.72386 8.5 7.5 8.72386 7.5 9V11C7.5 11.2761 7.72386 11.5 8 11.5C8.27614 11.5'
    + ' 8.5 11.2761 8.5 11V9C8.5 8.72386 8.27614 8.5 8 8.5Z" fill="currentColor" stroke="none"/>',

  // expui/general/unlocked.svg / unlocked_dark.svg
  unlocked: '<path fill-rule="evenodd" clip-rule="evenodd"'
    + ' d="M10 5C10 3.34315 11.3431 2 13 2C14.6569 2 16 3.34315 16 5V6.5C16 6.77614 15.7761 7 15.5'
    + ' 7C15.2239 7 15 6.77614 15 6.5V5C15 3.89543 14.1046 3 13 3C11.8954 3 11 3.89543 11'
    + ' 5V6C12.1046 6 13 6.89543 13 8V12C13 13.1046 12.1046 14 11 14H5C3.89543 14 3 13.1046 3'
    + ' 12V8C3 6.89543 3.89543 6 5 6H10V5ZM5 7H10.5H11C11.5523 7 12 7.44772 12 8V12C12 12.5523'
    + ' 11.5523 13 11 13H5C4.44772 13 4 12.5523 4 12V8C4 7.44772 4.44772 7 5 7ZM8 8.5C7.72386 8.5'
    + ' 7.5 8.72386 7.5 9V11C7.5 11.2761 7.72386 11.5 8 11.5C8.27614 11.5 8.5 11.2761 8.5 11'
    + ' V9C8.5 8.72386 8.27614 8.5 8 8.5Z" fill="currentColor" stroke="none"/>',

  // expui/status/infoOutline.svg / infoOutline_dark.svg: the hollow info
  // mark. The platform grays it per theme; the gray is rewritten to
  // currentColor so whoever hosts it decides the paint.
  "info-outline": '<path fill-rule="evenodd" clip-rule="evenodd"'
    + ' d="M8 1C4.13401 1 1 4.13401 1 8C1 11.866 4.13401 15 8 15C11.866 15 15 11.866 15 8'
    + 'C15 4.13401 11.866 1 8 1ZM8 2C4.68629 2 2 4.68629 2 8C2 11.3137 4.68629 14 8 14'
    + 'C11.3137 14 14 11.3137 14 8C14 4.68629 11.3137 2 8 2Z" fill="currentColor" stroke="none"/>'
    + '<path d="M7 11C7 11.5523 7.44772 12 8 12C8.55228 12 9 11.5523 9 11L9 8'
    + 'C9 7.94771 8.55228 7 8 7C7.44771 7 7 7.44772 7 8L7 11Z" fill="currentColor" stroke="none"/>'
    + '<path d="M8 4C7.44771 4 7 4.44772 7 5C7 5.55228 7.44771 6 8 6C8.55228 6 9 5.55228 9 5'
    + 'C9 4.44772 8.55228 4 8 4Z" fill="currentColor" stroke="none"/>',

  // Drawn at the same 1 stroke as the run icon it stands in for, so swapping one
  // for the other does not read as a change in weight.
  spinner: '<path d="M13.5 8a5.5 5.5 0 1 1-3.8-5.23" stroke-width="1"/>',

  // Completion row marks: the platform's own round letter icons, one per kind:
  // a property, a method, a class, a live template, a plain variable. These
  // keep their own palette instead of following currentColor, the way the Kotlin
  // brand mark does: the disc + the ink each read an --icon-node-* token, so
  // the theme swap paints the _dark.svg twin of every mark.
  //
  // expui/nodes/property.svg / property_dark.svg
  "completion-val": '<circle cx="8" cy="8" r="6.5"'
    + ' style="fill:var(--icon-node-val-bg);stroke:var(--icon-node-val-ink)"/>'
    + '<path d="M8.57081 5C7.72702 5 7.06335 5.37098 6.68265 5.99832V5.12281H5.7002V12'
    + 'H6.70312V9.52032C7.08689 10.1272 7.74186 10.4854 8.57081 10.4854C10.0291 10.4854'
    + ' 11.0474 9.36988 11.0474 7.74269C11.0474 6.1155 10.0291 5 8.57081 5ZM8.36101 9.5848'
    + 'C7.38367 9.5848 6.70312 8.8326 6.70312 7.74269C6.70312 6.65278 7.38367 5.90058'
    + ' 8.36101 5.90058C9.33324 5.90058 10.0036 6.65278 10.0036 7.74269C10.0036 8.8326'
    + ' 9.33324 9.5848 8.36101 9.5848Z" style="fill:var(--icon-node-val-ink)"/>',

  // expui/nodes/method.svg / method_dark.svg
  "completion-fun": '<circle cx="8" cy="8" r="6.5"'
    + ' style="fill:var(--icon-node-fun-bg);stroke:var(--icon-node-fun-ink)"/>'
    + '<path d="M10.0657 5.24573C9.25361 5.24573 8.636 5.6473 8.29593 6.31537C8.00368 5.64257'
    + ' 7.40552 5.24573 6.6071 5.24573C5.84915 5.24573 5.28147 5.60946 4.96189 6.24862V5.37188H4'
    + 'V10.7543H4.99869V7.59001C4.99869 6.68068 5.5138 6.10775 6.32852 6.10775C7.0749 6.10775'
    + ' 7.50591 6.61761 7.50591 7.43758V10.7543H8.49409V7.59001C8.49409 6.68068 9.01971 6.10775'
    + ' 9.82392 6.10775C10.5756 6.10775 11.0013 6.61761 11.0013 7.43758V10.7543H12V7.35874'
    + 'C12 6.07096 11.2168 5.24573 10.0657 5.24573Z" style="fill:var(--icon-node-fun-ink)"/>',

  // expui/nodes/class.svg / class_dark.svg
  "completion-type": '<circle cx="8" cy="8" r="6.5"'
    + ' style="fill:var(--icon-node-type-bg);stroke:var(--icon-node-type-ink)"/>'
    + '<path d="M8.13295 11.5C9.61223 11.5 10.8836 10.6105 11.2075 9.33909H10.2213'
    + 'C9.90229 10.0739 9.11914 10.6057 8.13295 10.6057C6.77936 10.6057 5.80284 9.51796'
    + ' 5.80284 8C5.80284 6.48204 6.77936 5.39434 8.13295 5.39434C9.11914 5.39434 9.90229 5.92611'
    + ' 10.2213 6.66091H11.2075C10.8836 5.3895 9.61223 4.5 8.13295 4.5C6.21859 4.5 4.79248 5.99378'
    + ' 4.79248 8C4.79248 10.0062 6.21859 11.5 8.13295 11.5Z"'
    + ' style="fill:var(--icon-node-type-ink)"/>',

  // expui/nodes/template.svg / template_dark.svg
  "completion-snippet": '<path d="M3.5 13.5H12.5"'
    + ' style="stroke:var(--icon-node-snippet-ink)" stroke-linecap="round"/>'
    + '<path d="M14 11.5H2C1.72386 11.5 1.5 11.2761 1.5 11V10C1.5 9.72386 1.72386 9.5 2 9.5H7'
    + 'L5.54631 4.41208C5.27253 3.45386 5.99203 2.5 6.9886 2.5H9.0114C10.008 2.5 10.7275 3.45386'
    + ' 10.4537 4.41208L9 9.5H14C14.2761 9.5 14.5 9.72386 14.5 10V11C14.5 11.2761 14.2761 11.5'
    + ' 14 11.5Z" style="stroke:var(--icon-node-snippet-ink)" stroke-linecap="round"'
    + ' stroke-linejoin="round"/>',

  // expui/nodes/variable.svg / variable_dark.svg
  "completion-word": '<circle cx="8" cy="8" r="6.5"'
    + ' style="fill:var(--icon-node-word-bg);stroke:var(--icon-node-word-ink)"/>'
    + '<path d="M7.5459 11.4H8.46582L10.8213 5.40002H9.79004L8.04395 10.1051L6.23926 5.40002'
    + 'H5.17871L7.5459 11.4Z" style="fill:var(--icon-node-word-ink)"/>',

  // Tool-call rows. Extracted verbatim from the IntelliJ Platform build in
  // PyCharm.app/Contents/lib/intellij.platform.ide.jar (expui/...), Apache 2.0,
  // Copyright 2000-2025 JetBrains s.r.o. and contributors. Only the platform's
  // #6C707E gray is rewritten to currentColor and its 1px stroke is pinned, since
  // the Icon component defaults to 1.5; the geometry is untouched.

  // expui/general/show.svg, AllIconsKeys.General.Show
  show: '<circle cx="8" cy="8" r="2.5" stroke-width="1"/>'
    + '<path d="M3.34803 11.4645C2.03281 10.5496 1.01443 9.31863 0.529751 8C1.01443 6.68137'
    + ' 2.03281 5.45039 3.34803 4.53545C4.71396 3.58524 6.36604 3 8 3C9.63396 3 11.286 3.58524'
    + ' 12.652 4.53545C13.9672 5.45039 14.9856 6.68137 15.4702 8C14.9856 9.31863 13.9672 10.5496'
    + ' 12.652 11.4645C11.286 12.4148 9.63396 13 8 13C6.36604 13 4.71396 12.4148 3.34803 11.4645Z"'
    + ' stroke-width="1"/>',

  // expui/general/search.svg, AllIconsKeys.Actions.Search
  search: '<circle cx="7" cy="7" r="4.5" stroke-width="1"/>'
    + '<path d="M10.1992 10.2002L13.4992 13.4961" stroke-width="1" stroke-linecap="round"/>',

  // expui/general/projectWideAnalysisOff.svg,
  // AllIconsKeys.Actions.ProjectWideAnalysisOff
  "project-wide-analysis-off": '<path fill-rule="evenodd" clip-rule="evenodd" fill="currentColor"'
    + ' stroke="none" d="M7.5 0.995728H8.5V7.13378C8.7989 7.30669 9 7.62986 9 8C9 8.55228'
    + ' 8.55228 9 8 9C7.44772 9 7 8.55228 7 8C7 7.62986 7.2011 7.30669 7.5 7.13378V5.04148'
    + 'C6.08114 5.27952 5 6.5135 5 8C5 9.65685 6.34315 11 8 11C9.65685 11 11 9.65685 11 8'
    + 'C11 7.29897 10.7596 6.65411 10.3566 6.14337L11.0674 5.43258C11.6495 6.12731 12 7.02273'
    + ' 12 8C12 10.2091 10.2091 12 8 12C5.79086 12 4 10.2091 4 8C4 5.96019 5.52684 4.277'
    + ' 7.5 4.03095V2.02054C4.42023 2.27461 2 4.85467 2 8C2 11.3137 4.68629 14 8 14'
    + 'C11.3137 14 14 11.3137 14 8C14 6.47037 13.4276 5.07443 12.4853 4.01469L13.1935 3.30653'
    + 'C14.3162 4.54812 15 6.19421 15 8C15 11.866 11.866 15 8 15C4.13401 15 1 11.866 1 8'
    + 'C1 4.30212 3.86736 1.27396 7.5 1.01758V0.995728Z"/>',

  // expui/nodes/ppWeb.svg, AllIconsKeys.Nodes.PpWeb
  "pp-web": '<circle cx="8" cy="8" r="6.5" stroke-width="1"/>'
    + '<path d="M10.5 8C10.5 9.8819 10.1721 11.5619 9.66175 12.7528C9.40618 13.3491'
    + ' 9.11471 13.7997 8.81652 14.094C8.52048 14.3862 8.24513 14.5 8 14.5'
    + 'C7.75487 14.5 7.47952 14.3862 7.18348 14.094C6.88529 13.7997 6.59382 13.3491'
    + ' 6.33825 12.7528C5.82788 11.5619 5.5 9.8819 5.5 8C5.5 6.1181 5.82788 4.43808'
    + ' 6.33825 3.24721C6.59382 2.65089 6.88529 2.20026 7.18348 1.90597C7.47952 1.6138'
    + ' 7.75487 1.5 8 1.5C8.24513 1.5 8.52048 1.6138 8.81652 1.90597C9.11471 2.20026'
    + ' 9.40618 2.65089 9.66175 3.24721C10.1721 4.43808 10.5 6.1181 10.5 8Z" stroke-width="1"/>'
    + '<path d="M14.2218 5.5H1.77734" stroke-width="1"/>'
    + '<path d="M14.2218 10.5H1.77734" stroke-width="1"/>',

  // expui/general/externalTools.svg, AllIconsKeys.General.ExternalTools
  "external-tools": '<path stroke-width="1" stroke-miterlimit="10"'
    + ' d="M6.04 9.55 3.3 12.29c-.2.2-.29.45-.29.71 0 .26.1.51.29.71.19.2.45.29.71.29'
    + '.26 0 .51-.1.71-.29l2.74-2.74m3.51-6.36L9.55 6.04m1.41 1.41 1.43-1.42"/>'
    + '<path stroke-width="1" stroke-linejoin="round"'
    + ' d="m14.19 7.83.31-3.31-3.02-3.02-1.81 1.81 4.52 4.52Zm-1.9 5.88c.2.2.45.29.71.29'
    + '.26 0 .51-.1.71-.29.2-.19.29-.45.29-.71 0-.26-.1-.51-.29-.71L7.2 5.78'
    + 'c.19-.39.3-.82.3-1.29 0-1.66-1.34-3-3-3-.13 0-.26.02-.38.04l-.53.53 1.73 1.73L3.8 5.31'
    + ' 2.07 3.58l-.53.53c-.02.13-.04.25-.04.38 0 1.66 1.34 3 3 3 .46 0 .89-.11 1.29-.3l6.51 6.51-.01.01Z"/>',

  // expui/actions/report.svg, AllIconsKeys.Actions.Report
  report: '<path fill-rule="evenodd" clip-rule="evenodd" fill="currentColor" stroke="none"'
    + ' d="M8 1L10 2H12C12.5523 2 13 2.44772 13 3V9C13 9.55229 12.5523 10 12 10H10L8 9H4V15H3V1H8Z'
    + 'M4 2V8H8.23607L10.2361 9H12V3H9.76393L7.76393 2H4Z"/>',

  // expui/nodes/services.svg, AllIconsKeys.Nodes.Services
  services: '<path d="M15.2117 7.50028C15.3901 7.80954 15.3901 8.19046 15.2117 8.49972'
    + 'L12.0386 13.9997C11.86 14.3093 11.5298 14.5 11.1724 14.5L4.82756 14.5'
    + 'C4.47018 14.5 4.13997 14.3093 3.96138 13.9997L0.788301 8.49972'
    + 'C0.609882 8.19046 0.609882 7.80954 0.788301 7.50028L3.96138 2.00028'
    + 'C4.13997 1.69072 4.47018 1.5 4.82756 1.5L11.1724 1.5C11.5298 1.5 11.86 1.69072'
    + ' 12.0386 2.00028L15.2117 7.50028Z" stroke-width="1"/>'
    + '<path d="M10.5 8.43301L6.75 10.5981C6.41667 10.7905 6 10.55 6 10.1651L6 5.83493'
    + 'C6 5.45004 6.41667 5.20947 6.75 5.40192L10.5 7.56699C10.8333 7.75944 10.8333 8.24056'
    + ' 10.5 8.43301Z" stroke-width="1"/>',

  // expui/actions/checked.svg, AllIconsKeys.Actions.Checked. The platform draws
  // this one at 1.5, so the path keeps its own width.
  checked: '<path d="M2.5 8.25L6 11.75L13.5 4.25" stroke-width="1.5"'
    + ' stroke-linecap="round" stroke-linejoin="round"/>',

  // expui/general/groups.svg, AllIconsKeys.General.Groups
  groups: '<rect x="1.5" y="1.5" width="5" height="5" rx="0.5" stroke-width="1"/>'
    + '<rect x="1.5" y="8.5" width="5" height="5" rx="0.5" stroke-width="1"/>'
    + '<rect x="8.5" y="8.5" width="5" height="5" rx="0.5" stroke-width="1"/>'
    + '<rect x="8.5" y="1.5" width="5" height="5" rx="0.5" stroke-width="1"/>',

  // The plugin's own command mark (plugin resources icons/cmd/cmd.svg), the same
  // file GradumIcons.Ran draws. Its #EBECF0 backing plate is dropped and the
  // #6C707E ink rewritten to currentColor, following the monochrome rule above.
  cmd: '<path fill-rule="evenodd" clip-rule="evenodd" fill="currentColor" stroke="none"'
    + ' d="M13 3H3C2.44772 3 2 3.44772 2 4V12C2 12.5523 2.44772 13 3 13H13C13.5523 13 14 12.5523'
    + ' 14 12V4C14 3.44772 13.5523 3 13 3ZM3 2C1.89543 2 1 2.89543 1 4V12C1 13.1046 1.89543 14'
    + ' 3 14H13C14.1046 14 15 13.1046 15 12V4C15 2.89543 14.1046 2 13 2H3Z"/>'
    + '<path fill-rule="evenodd" clip-rule="evenodd" fill="currentColor" stroke="none"'
    + ' d="M7 10.5C7 10.2239 7.22386 10 7.5 10L10.5 10C10.7761 10 11 10.2239 11 10.5'
    + 'C11 10.7761 10.7761 11 10.5 11L7.5 11C7.22386 11 7 10.7761 7 10.5Z"/>'
    + '<path fill-rule="evenodd" clip-rule="evenodd" fill="currentColor" stroke="none"'
    + ' d="M4.12643 5.16659C4.32169 4.97133 4.63827 4.97133 4.83353 5.16659L6.81343 7.14649'
    + 'C7.00869 7.34175 7.00869 7.65833 6.81343 7.85359L4.83353 9.83349'
    + 'C4.63827 10.0288 4.32169 10.0288 4.12643 9.83349C3.93116 9.63823 3.93116 9.32165'
    + ' 4.12643 9.12639L5.75277 7.50004L4.12643 5.8737C3.93116 5.67843 3.93116 5.36185 4.12643 5.16659Z"/>',

  // The plugin's bullet-list mark (plugin resources icons/bullet-list/
  // bullet-list.svg), GradumIcons.BulletList, gray rewritten to currentColor.
  "bullet-list": '<path fill="currentColor" stroke="none"'
    + ' d="M3.5 3.25C3.91421 3.25 4.25 2.91421 4.25 2.5C4.25 2.08579 3.91421 1.75 3.5 1.75'
    + 'C3.08579 1.75 2.75 2.08579 2.75 2.5C2.75 2.91421 3.08579 3.25 3.5 3.25Z"/>'
    + '<path fill="currentColor" stroke="none"'
    + ' d="M7.5 2C7.22386 2 7 2.22386 7 2.5C7 2.77614 7.22386 3 7.5 3H13.5C13.7761 3'
    + ' 14 2.77614 14 2.5C14 2.22386 13.7761 2 13.5 2H7.5Z"/>'
    + '<path fill="currentColor" stroke="none"'
    + ' d="M7.5 7C7.22386 7 7 7.22386 7 7.5C7 7.77614 7.22386 8 7.5 8H13.5C13.7761 8'
    + ' 14 7.77614 14 7.5C14 7.22386 13.7761 7 13.5 7H7.5Z"/>'
    + '<path fill="currentColor" stroke="none"'
    + ' d="M7.5 12C7.22386 12 7 12.2239 7 12.5C7 12.7761 7.22386 13 7.5 13H13.5'
    + 'C13.7761 13 14 12.7761 14 12.5C14 12.2239 13.7761 12 13.5 12H7.5Z"/>'
    + '<path fill="currentColor" stroke="none"'
    + ' d="M3.5 8.25C3.91421 8.25 4.25 7.91421 4.25 7.5C4.25 7.08579 3.91421 6.75 3.5 6.75'
    + 'C3.08579 6.75 2.75 7.08579 2.75 7.5C2.75 7.91421 3.08579 8.25 3.5 8.25Z"/>'
    + '<path fill="currentColor" stroke="none"'
    + ' d="M4.25 12.5C4.25 12.9142 3.91421 13.25 3.5 13.25C3.08579 13.25 2.75 12.9142 2.75 12.5'
    + 'C2.75 12.0858 3.08579 11.75 3.5 11.75C3.91421 11.75 4.25 12.0858 4.25 12.5Z"/>',
};
