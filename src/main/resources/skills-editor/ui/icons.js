// Inline SVG sprite. Icons are stored as inner markup for a 16x16 viewBox and
// rendered by the Icon component, so no network font or CDN script is needed.
//
// Vendored from the IntelliJ platform, all under Apache 2.0,
// Copyright 2000-2025 JetBrains s.r.o. and contributors:
//   lib/intellij.platform.ide.jar      expui/{actions,toolwindows,general,status,run,build,nodes}/*
//   plugins/Kotlin/lib/intellij.kotlin.base.resources.jar
//                                          org/jetbrains/kotlin/idea/icons/expui/kotlin_dark.svg
//
// Monochrome icons have their grey rewritten to currentColor so they follow
// hover and selected states, drawn at the platform stroke width of 1. The
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

  // expui/toolwindows/project.svg, the folder glyph the platform draws for tree
  // roots.
  folder: '<path d="M8.15132 4.35836L8.29689 4.5H8.5H13C13.8284 4.5 14.5 5.17157 14.5 6V12.1333'
    + 'C14.5 12.919 13.9104 13.5 13.25 13.5H2.75C2.08955 13.5 1.5 12.919 1.5 12.1333V3.86667'
    + 'C1.5 3.08099 2.08955 2.5 2.75 2.5H6.03823C6.16847 2.5 6.29357 2.55082 6.38691 2.64164'
    + 'L8.15132 4.35836Z" stroke-width="1" stroke-linejoin="miter"/>',

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
  // brand mark does: the disc and the ink each read an --icon-node-* token, so
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
};
