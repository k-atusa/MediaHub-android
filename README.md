# mc-ping-check

A web service to check if a Minecraft server is online using TCP ping. This is useful for integration with uptime monitoring services like Betterstack that don't support direct TCP ping.

## Features

- Check Minecraft server status via HTTP GET request
- Returns simple "online" or "offline" text response
- Default Minecraft port (25565) if not specified
- Configurable timeout for TCP connections
- Health check endpoint for monitoring

## Installation

```bash
npm install
npm run build
```

## Usage

Start the server:

```bash
npm start
```

The server will run on port 3000 by default. You can change this with the `PORT` environment variable:

```bash
PORT=8080 npm start
```

### API Endpoints

#### Check Server Status

```
GET /?url=<server:port>
```

**Parameters:**
- `url` (required): The Minecraft server address in format `host:port` or just `host` (defaults to port 25565)

**Response:**
- `online` - Server is reachable
- `offline` - Server is not reachable

**Examples:**

```bash
# Check with specific port
curl "http://localhost:3000/?url=play.example.com:25565"

# Check with default port (25565)
curl "http://localhost:3000/?url=play.example.com"
```

#### Health Check

```
GET /health
```

**Response:** `ok`

## Integration with Betterstack

1. Deploy this service to a hosting platform (e.g., Railway, Render, Heroku)
2. In Betterstack, create a new HTTP(s) monitor
3. Set the URL to: `https://your-service.com/?url=your-minecraft-server:25565`
4. Set "Keyword exists" to check for the word `online`
5. Betterstack will now monitor your Minecraft server's uptime!

## Development

```bash
# Build TypeScript
npm run build

# Build and run
npm run dev
```

## License

MIT