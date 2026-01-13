package com.jubitus.jubituschunks.commands;

import com.jubitus.jubituschunks.JubitusChunksMod;
import com.jubitus.jubituschunks.config.JubitusChunksConfig;
import com.jubitus.jubituschunks.net.NetworkHandler;
import com.jubitus.jubituschunks.net.msg.PacketViewOpen;
import com.jubitus.jubituschunks.pregen.PregenManager;
import com.jubitus.jubituschunks.pregen.state.PregenState;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

public class CommandJubitusChunks extends CommandBase {

    @Override
    public String getName() {
        return "jubituschunks";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/jubituschunks <radiusBlocks> [x] [z] [skipExisting|force]\n"
                + "/jubituschunks stop\n"
                + "/jubituschunks resume [stepIndex] [x] [z]"
        + "/jubituschunks view <radiusBlocks>\n";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {

        if (args.length >= 1 && "view".equalsIgnoreCase(args[0])) {
            if (!(sender.getCommandSenderEntity() instanceof EntityPlayerMP)) {
                sender.sendMessage(new TextComponentString("This command must be run by a player."));
                return;
            }

            if (args.length != 2) {
                throw new WrongUsageException("/jubituschunks view <radiusBlocks>");
            }

            int radiusBlocks = parseInt(args[1], 16, 50_000_000);

            EntityPlayerMP p = (EntityPlayerMP) sender.getCommandSenderEntity();

            // Tell client to open GUI
            NetworkHandler.NET.sendTo(new PacketViewOpen(radiusBlocks), p);

            sender.sendMessage(new TextComponentString("Opened chunk viewer (radiusBlocks=" + radiusBlocks + ")."));
            return;
        }


        if (args.length >= 1 && "resume".equalsIgnoreCase(args[0])) {
            WorldServer world = getWorldForSender(server, sender);

            PregenState st = PregenState.load(world);
            if (st == null) {
                sender.sendMessage(new TextComponentString("No saved pregen state for this dimension."));
                return;
            }

            // Defaults from saved state
            int cx = st.centerChunkX;
            int cz = st.centerChunkZ;
            int chunkRadius = st.chunkRadius;
            boolean skipExisting = st.skipExisting;
            boolean verifyExisting = st.verifyExisting;
            boolean populateViaGenerator = st.populateViaGenerator;

            long stepIndex = st.spiralSteps;

// Optional: force safe defaults on resume (you can keep this if you want)
            skipExisting = true;          // recommended
            verifyExisting = true;        // recommended if you want to repair half-finished chunks
// populateViaGenerator stays whatever was used before


            long d = (long) chunkRadius * 2L + 1L;
            long maxSteps = d * d;

            if (args.length >= 2) {
                stepIndex = parseLong(args[1], 0, maxSteps);
            } else {
                // clamp loaded value too (in case radius changed or file got edited)
                stepIndex = Math.max(0, Math.min(stepIndex, maxSteps));
            }

            // /pregenMill resume 405101 <x> <z>  (block coords)
            if (args.length >= 4) {
                int bx = parseInt(args[2]);
                int bz = parseInt(args[3]);
                cx = bx >> 4;
                cz = bz >> 4;
            }

            // Build a start BlockPos from chunk center (optional)
            BlockPos start = new BlockPos((cx << 4) + 8, world.getSeaLevel(), (cz << 4) + 8);
            int radiusBlocks = chunkRadius * 16;

            EntityPlayerMP initiator = sender.getCommandSenderEntity() instanceof EntityPlayerMP
                    ? (EntityPlayerMP) sender.getCommandSenderEntity()
                    : null;

            boolean started = PregenManager.startResumable(
                    server, world, start, radiusBlocks, initiator,
                    skipExisting, verifyExisting, populateViaGenerator,
                    stepIndex
            );


            if (started) {
                sender.sendMessage(new TextComponentString(
                        "Resumed mill pregen at step=" + stepIndex +
                                " centerChunk=" + cx + "," + cz +
                                " radiusBlocks=" + radiusBlocks +
                                " skipExisting=" + skipExisting +
                                " verifyExisting=" + verifyExisting +
                                " populateViaGenerator=" + populateViaGenerator
                ));
            } else {
                sender.sendMessage(new TextComponentString("A pregen is already running in this dimension. Use /pregenMill stop first."));
            }
            return;
        }


        if (args.length == 1 && "stop".equalsIgnoreCase(args[0])) {
            // stop in sender's dimension if possible; otherwise overworld
            WorldServer world = getWorldForSender(server, sender);
            boolean stopped = PregenManager.stop(world.provider.getDimension());
            sender.sendMessage(new TextComponentString(stopped ? "Mill pregen stopped." : "No mill pregen running here."));
            return;
        }

        if (args.length != 1 && args.length != 2 && args.length != 3 && args.length != 4) {
            throw new WrongUsageException(getUsage(sender));
        }


        int radiusBlocks = parseInt(args[0], 16, 50_000_000); // allow huge radiuses safely

        boolean skipExisting = JubitusChunksConfig.GENERAL.skipAlreadyGeneratedChunks;

// Optional flag is last argument when args length is 2 or 4
        if (args.length == 2 || args.length == 4) {
            String flag = args[args.length - 1].toLowerCase();

            if (flag.equals("skipexisting") || flag.equals("skip")) {
                skipExisting = true;
            } else if (flag.equals("force") || flag.equals("noskip")) {
                skipExisting = false;
            } else {
                // If they typed something unknown, show usage
                throw new WrongUsageException(getUsage(sender));
            }
        }


        WorldServer world = getWorldForSender(server, sender);

        BlockPos start;

        if (args.length == 1 || args.length == 2) {
            // player pos if player, else spawn
            EntityPlayerMP player = sender.getCommandSenderEntity() instanceof EntityPlayerMP
                    ? (EntityPlayerMP) sender.getCommandSenderEntity()
                    : null;

            if (player != null) {
                world = player.getServerWorld();
                start = player.getPosition();
            } else {
                start = world.getSpawnPoint();
            }
        } else {
            // args.length == 3 or 4 -> coordinates are args[1], args[2]
            int x = parseInt(args[1]);
            int z = parseInt(args[2]);
            start = new BlockPos(x, world.getSeaLevel(), z);
        }


        EntityPlayerMP initiator = sender.getCommandSenderEntity() instanceof EntityPlayerMP
                ? (EntityPlayerMP) sender.getCommandSenderEntity()
                : null;

        boolean started = PregenManager.start(server, world, start, radiusBlocks, initiator, skipExisting);


        if (started) {
            String mode = skipExisting ? "skipExisting" : "force";
            String msg = "Started mill-style pregeneration. Radius=" + radiusBlocks +
                    " blocks. Start=" + start.getX() + " " + start.getZ() +
                    " Dim=" + world.provider.getDimension() +
                    " Mode=" + mode;

            sender.sendMessage(new TextComponentString(msg));
            JubitusChunksMod.LOGGER.info(msg); // <-- ALSO prints to server console even if player ran it
        } else {
            sender.sendMessage(new TextComponentString(
                    "A pregen is already running in this dimension. Use /pregenMill stop first."
            ));
        }


    }

    private WorldServer getWorldForSender(MinecraftServer server, ICommandSender sender) {
        // For console, sender.getEntityWorld() may still work, but overworld is safest fallback.
        if (sender.getEntityWorld() instanceof WorldServer) {
            return (WorldServer) sender.getEntityWorld();
        }
        return server.getWorld(0);
    }
}
