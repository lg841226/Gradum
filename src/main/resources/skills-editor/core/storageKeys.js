// Every persistent key in one table. Features never spell a storage key, so a
// rename cannot leave one reader on the old name.
//
// index.html repeats `theme` in its pre-paint script because that script has to
// run before modules load; it is the one literal outside this table.
export const STORAGE_KEYS = {
  model: "legatus.model",
  permission: "legatus.permission",
  projectRoot: "legatus.projectRoot",
  chatTabs: "legatus.chatTabs",
  theme: "gradum.skillsEditor.theme",
  layout: "gradum.skillsEditor.layout",
};
