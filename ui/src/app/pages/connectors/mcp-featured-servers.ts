/** A discovery shortcut, not a cached installation recipe or a registry popularity ranking. */
export interface FeaturedMcpServer {
  readonly registryName: string;
  readonly name: string;
  readonly logo: string;
  readonly category: string;
  readonly description: string;
}

/**
 * Developer picks checked against the Official MCP Registry on 2026-11-08.
 * Selection balances MCP repository interest, package downloads and common development workflows.
 * Remote-only services do not expose comparable installation counts; order is editorial, not a usage ranking.
 * Keep exact publisher IDs here. Fetch current configuration from the registry only when a card is selected.
 * Artwork provenance: ui/public/mcp-logos/SOURCES.md.
 */
export const FEATURED_MCP_SERVERS: readonly FeaturedMcpServer[] = [
  { registryName: 'io.github.github/github-mcp-server', name: 'GitHub', logo: 'mcp-logos/github.png', category: 'Code & collaboration', description: 'Work with repositories, issues, pull requests and GitHub Actions.' },
  { registryName: 'io.github.upstash/context7', name: 'Context7', logo: 'mcp-logos/context7.png', category: 'Documentation', description: 'Get current library documentation and code examples while you build.' },
  { registryName: 'io.github.microsoft/playwright-mcp', name: 'Playwright', logo: 'mcp-logos/playwright.svg', category: 'Browser testing', description: 'Automate browser interactions and test your web application.' },
  { registryName: 'io.github.ChromeDevTools/chrome-devtools-mcp', name: 'Chrome DevTools', logo: 'mcp-logos/chrome-devtools.png', category: 'Browser debugging', description: 'Inspect pages, debug network requests and investigate performance.' },
  { registryName: 'com.figma.mcp/mcp', name: 'Figma', logo: 'mcp-logos/figma.png', category: 'Design', description: 'Bring design context, components and layout details into your code.' },
  { registryName: 'com.supabase/mcp', name: 'Supabase', logo: 'mcp-logos/supabase.png', category: 'Databases', description: 'Explore schemas, run SQL and manage your Supabase projects.' },
  { registryName: 'com.neon/mcp', name: 'Neon', logo: 'mcp-logos/neon.svg', category: 'Databases', description: 'Query Postgres and work with database branches and migrations.' },
  { registryName: 'io.github.mongodb-js/mongodb-mcp-server', name: 'MongoDB', logo: 'assets/integrations/mongodb.svg', category: 'Databases', description: 'Explore collections, query documents and manage MongoDB deployments.' },
  { registryName: 'io.github.getsentry/sentry-mcp', name: 'Sentry', logo: 'mcp-logos/sentry.png', category: 'Observability', description: 'Investigate errors and traces with context from your application.' },
  { registryName: 'app.linear/linear', name: 'Linear', logo: 'mcp-logos/linear.png', category: 'Project management', description: 'Find and update issues, plan projects and track engineering work.' },
  { registryName: 'com.atlassian/atlassian-mcp-server', name: 'Atlassian', logo: 'mcp-logos/atlassian.png', category: 'Project management', description: 'Connect Jira issues and Confluence knowledge to your workflow.' },
  { registryName: 'com.notion/mcp', name: 'Notion', logo: 'mcp-logos/notion.png', category: 'Knowledge', description: 'Search and update project documentation, pages and databases.' },
  { registryName: 'com.vercel/vercel-mcp', name: 'Vercel', logo: 'mcp-logos/vercel.png', category: 'Deployment', description: 'Inspect projects, deployments and logs on Vercel.' },
  { registryName: 'com.cloudflare.mcp/mcp', name: 'Cloudflare', logo: 'mcp-logos/cloudflare.jpg', category: 'Cloud documentation', description: 'Find Cloudflare documentation for Workers and cloud services.' },
  { registryName: 'com.stripe/mcp', name: 'Stripe', logo: 'mcp-logos/stripe.png', category: 'Payments', description: 'Build payment integrations and work with Stripe account resources.' },
  { registryName: 'io.github.firecrawl/firecrawl-mcp-server', name: 'Firecrawl', logo: 'mcp-logos/firecrawl.png', category: 'Web data', description: 'Search, crawl and extract structured content from websites.' },
  { registryName: 'io.github.brave/brave-search-mcp-server', name: 'Brave Search', logo: 'mcp-logos/brave.png', category: 'Web search', description: 'Search the web for technical answers, news and reference material.' },
  { registryName: 'ai.exa/exa', name: 'Exa', logo: 'mcp-logos/exa.png', category: 'Code & web search', description: 'Find code examples, technical sources and relevant web content.' },
  { registryName: 'io.github.tavily-ai/tavily-mcp', name: 'Tavily', logo: 'mcp-logos/tavily.png', category: 'Web research', description: 'Search technical sources and extract web content for your research.' },
  { registryName: 'co.huggingface/hf-mcp-server', name: 'Hugging Face', logo: 'mcp-logos/huggingface.svg', category: 'Machine learning', description: 'Discover models, datasets, papers and Spaces on the Hugging Face Hub.' },
];
