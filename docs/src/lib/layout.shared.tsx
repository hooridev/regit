import type { BaseLayoutProps } from 'fumadocs-ui/layouts/shared';
import { appName, gitConfig } from './shared';

export function baseOptions(): BaseLayoutProps {
  return {
    nav: {
      title: (
        <span className="inline-flex items-center gap-2 font-semibold tracking-tight">
          <svg
            xmlns="http://www.w3.org/2000/svg"
            viewBox="0 0 64 64"
            className="size-5 shrink-0"
            aria-hidden="true"
          >
            <rect width="64" height="64" rx="14" fill="#e11d48" />
            <text
              x="32"
              y="32"
              dy="0.35em"
              textAnchor="middle"
              fontFamily="ui-sans-serif, system-ui, -apple-system, sans-serif"
              fontSize="34"
              fontWeight="700"
              fill="#ffffff"
            >
              R
            </text>
          </svg>
          <span>{appName}</span>
        </span>
      ),
    },
    links: [
      { text: 'Dokumentation', url: '/docs', active: 'nested-url' },
      { text: 'Praxis-Rezepte', url: '/docs/extensions-and-recipes' },
    ],
    githubUrl: `https://github.com/${gitConfig.user}/${gitConfig.repo}`,
  };
}
