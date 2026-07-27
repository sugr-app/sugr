// import.meta.env.MODE is "dev"/"staging"/"prod" - see sugr dev/build's --env flag
// and .env/.env.dev/.env.staging/.env.prod. Add VITE_-prefixed vars here as needed.
export const env = {
  mode: import.meta.env.MODE,
  appTitle: import.meta.env.VITE_APP_TITLE as string | undefined,
};

export const isDevelopment = () => env.mode === 'dev';
export const isStaging = () => env.mode === 'staging';
export const isProduction = () => env.mode === 'prod';
