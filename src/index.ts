import express, { Request, Response } from 'express';
import { status } from 'minecraft-server-util';

const app = express();
const PORT = process.env.PORT || 3000;
const TIMEOUT_MS = 5000; // 5 seconds timeout for Minecraft server ping

/**
 * Checks if a Minecraft server is online using the Server List Ping protocol
 * @param host - The hostname or IP address
 * @param port - The port number
 * @returns Promise<boolean> - true if server is online, false otherwise
 */
async function checkMinecraftServer(host: string, port: number): Promise<boolean> {
  try {
    await status(host, port, { timeout: TIMEOUT_MS });
    return true;
  } catch (error) {
    return false;
  }
}

/**
 * Parses the server address from the URL parameter
 * @param url - The server address in format "host:port" or "host"
 * @returns { host: string, port: number } or null if invalid
 */
function parseServerAddress(url: string): { host: string; port: number } | null {
  const trimmedUrl = url.trim();
  if (!trimmedUrl) {
    return null;
  }
  
  const parts = trimmedUrl.split(':');
  
  if (parts.length === 1) {
    const host = parts[0].trim();
    if (!host) {
      return null;
    }
    // No port specified, use default Minecraft port
    return { host, port: 25565 };
  }
  
  if (parts.length === 2) {
    const host = parts[0].trim();
    if (!host) {
      return null;
    }
    
    const port = parseInt(parts[1], 10);
    
    if (isNaN(port) || port < 1 || port > 65535) {
      return null;
    }
    
    return { host, port };
  }
  
  return null;
}

// Main endpoint to check Minecraft server status
app.get('/', async (req: Request, res: Response) => {
  const url = req.query.url as string | undefined;
  
  if (!url) {
    res.status(400).send('Missing url parameter. Usage: /?url=server:port');
    return;
  }
  
  const serverAddress = parseServerAddress(url);
  
  if (!serverAddress) {
    res.status(400).send('Invalid server address format. Use: host:port or host');
    return;
  }
  
  const isOnline = await checkMinecraftServer(serverAddress.host, serverAddress.port);
  
  res.send(isOnline ? 'online' : 'offline');
});

app.listen(PORT, () => {
  console.log(`MC Ping Check server is running on port ${PORT}`);
  console.log(`Usage: http://localhost:${PORT}/?url=server:port`);
});
