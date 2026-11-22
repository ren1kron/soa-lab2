const paths = {
  organizations: 'M4 21V5l10-2v18 M14 9h6v12 M2 21h20 M8 8h2 M8 12h2 M8 16h2 M17 13h1 M17 17h1',
  employees: 'M16 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2 M19 15a4 4 0 0 1 3 4 M15 4a4 4 0 0 1 0 7 M14 8a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
  operations: 'M3 7h16l-4-4 M21 17H5l4 4 M19 7l-4 4 M5 17l4-4',
  plus: 'M12 5v14 M5 12h14',
  refresh: 'M20 5v5h-5 M20 10a8 8 0 1 0 0 5',
  info: 'M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0 M12 11v6 M12 7v.2',
  edit: 'm14 5 5 5 M4 20l5-1L21 7l-5-5L4 14z',
  remove: 'M3 6h18 M9 6V3h6v3 M6 6l1 15h10l1-15 M10 10v7 M14 10v7',
  expand: 'm9 5 7 7-7 7',
  sliders: 'M3 6h8 M15 6h6 M3 12h2 M9 12h12 M3 18h12 M19 18h2 M11 3v6 M5 9v6 M15 15v6',
  search: 'M16 16l5 5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  list: 'M4 5h16v14H4z M4 10h16 M9 10v9',
};

export default function Icon({name}: {name: keyof typeof paths}) {
  return <svg className="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d={paths[name]}/>
  </svg>;
}
