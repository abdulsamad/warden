// @ts-check
import { defineConfig } from 'astro/config';

export default defineConfig({
  site: 'https://warden.abdulsamad.dev',
  // Minifying drops the spaces between a line break and a link ("by<a>"). The page is small and
  // Cloudflare compresses it anyway.
  compressHTML: false,
  // No Markdown here, and Shiki's inline styles would break the CSP.
  markdown: { syntaxHighlight: false },
  security: {
    // A <meta> CSP with hashes for every script and style Astro emits. Directives a <meta> CSP
    // can't carry (frame-ancestors) are sent as headers from public/_headers.
    csp: {
      directives: [
        "default-src 'self'",
        "img-src 'self' data:",
        // The page reads the latest release (version, size, SHA-256) from GitHub's API.
        "connect-src https://api.github.com",
        "object-src 'none'",
        "base-uri 'none'",
        "form-action 'none'",
        "require-trusted-types-for 'script'",
      ],
    },
  },
});
