package journeymap.client.io;

import java.io.File;
import net.minecraft.client.Minecraft;

/** Tier-1 stub: the map directory JourneyMap is writing into (null = no world). */
public class FileHandler {
    public static File worldDir = new File("jm-test/mp/Test_Server");

    public static File getJMWorldDir(Minecraft minecraft) { return worldDir; }
}
