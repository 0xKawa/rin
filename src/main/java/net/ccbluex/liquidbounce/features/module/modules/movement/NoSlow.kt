// ===== File 1: NoSlow.kt =====

/*
 * LiquidBounce++ Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/PlusPlusMC/LiquidBouncePlusPlus/
 */
package net.ccbluex.liquidbounce.features.module.modules.movement

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.event.EventTarget
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.MotionEvent
import net.ccbluex.liquidbounce.event.SlowDownEvent
import net.ccbluex.liquidbounce.event.PacketEvent
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleInfo
import net.ccbluex.liquidbounce.features.module.modules.combat.KillAura
import net.ccbluex.liquidbounce.utils.ClientUtils
import net.ccbluex.liquidbounce.utils.MovementUtils
import net.ccbluex.liquidbounce.utils.PacketUtils
import net.ccbluex.liquidbounce.utils.timer.MSTimer
import net.ccbluex.liquidbounce.value.BoolValue
import net.ccbluex.liquidbounce.value.FloatValue
import net.ccbluex.liquidbounce.value.IntegerValue
import net.ccbluex.liquidbounce.value.ListValue
import net.minecraft.item.*
import net.minecraft.network.Packet
import net.minecraft.network.play.INetHandlerPlayServer
import net.minecraft.network.play.client.C0BPacketEntityAction
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition
import net.minecraft.network.play.client.C03PacketPlayer.C05PacketPlayerLook
import net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook
import net.minecraft.network.play.client.C07PacketPlayerDigging
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement
import net.minecraft.network.play.client.C09PacketHeldItemChange
import net.minecraft.network.play.server.S30PacketWindowItems
import net.minecraft.util.BlockPos
import net.minecraft.util.EnumFacing

@ModuleInfo(name = "NoSlow", spacedName = "No Slow", category = ModuleCategory.MOVEMENT, description = "Prevent you from getting slowed down by items (swords, foods, etc.) and liquids.")
class NoSlow : Module() {
    private val msTimer = MSTimer()
    private val modeValue = ListValue("PacketMode", arrayOf("Vanilla", "Watchdog", "OldWatchdog", "OldHypixel", "Blink", "Experimental", "NCP", "AAC", "AAC5", "Custom"), "Vanilla")
    private val blockForwardMultiplier = FloatValue("BlockForwardMultiplier", 1.0F, 0.2F, 1.0F, "x")
    private val blockStrafeMultiplier = FloatValue("BlockStrafeMultiplier", 1.0F, 0.2F, 1.0F, "x")
    private val consumeForwardMultiplier = FloatValue("ConsumeForwardMultiplier", 1.0F, 0.2F, 1.0F, "x")
    private val consumeStrafeMultiplier = FloatValue("ConsumeStrafeMultiplier", 1.0F, 0.2F, 1.0F, "x")
    private val bowForwardMultiplier = FloatValue("BowForwardMultiplier", 1.0F, 0.2F, 1.0F, "x")
    private val bowStrafeMultiplier = FloatValue("BowStrafeMultiplier", 1.0F, 0.2F, 1.0F, "x")
    val sneakForwardMultiplier = FloatValue("SneakForwardMultiplier", 1.0F, 0.3F, 1.0F, "x")
    val sneakStrafeMultiplier = FloatValue("SneakStrafeMultiplier", 1.0F, 0.3F, 1.0F, "x")
    private val customRelease = BoolValue("CustomReleasePacket", false, { modeValue.get().equals("custom", true) })
    private val customPlace = BoolValue("CustomPlacePacket", false, { modeValue.get().equals("custom", true) })
    private val customOnGround = BoolValue("CustomOnGround", false, { modeValue.get().equals("custom", true) })
    private val customDelayValue = IntegerValue("CustomDelay", 60, 0, 1000, "ms", { modeValue.get().equals("custom", true) })
    private val testValue = BoolValue("SendPacket", false, { modeValue.get().equals("watchdog", true) })
    private val ciucValue = BoolValue("CheckInUseCount", true, { modeValue.get().equals("blink", true) })
    private val packetTriggerValue = ListValue("PacketTrigger", arrayOf("PreRelease", "PostRelease"), "PostRelease", { modeValue.get().equals("blink", true) })
    private val debugValue = BoolValue("Debug", false, { modeValue.get().equals("watchdog", true) || modeValue.get().equals("blink", true) })

    // Soulsand
    val soulsandValue = BoolValue("Soulsand", true)
    val liquidPushValue = BoolValue("LiquidPush", true)

    private val blinkPackets = mutableListOf<Packet<INetHandlerPlayServer>>()
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastZ = 0.0
    private var lastOnGround = false

    private var fasterDelay = false
    private var placeDelay = 0L
    private val timer = MSTimer()

    override fun onEnable() {
        blinkPackets.clear()
        msTimer.reset()
    }   

    override fun onDisable() {
        blinkPackets.forEach {
            PacketUtils.sendPacketNoEvent(it)
        }
        blinkPackets.clear()
    }

    override val tag: String?
        get() = modeValue.get()

    private fun sendPacket(event : MotionEvent, sendC07 : Boolean, sendC08 : Boolean, delay : Boolean, delayValue : Long, onGround : Boolean, watchDog : Boolean = false) {
        val digging = C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos(-1,-1,-1), EnumFacing.DOWN)
        val blockPlace = C08PacketPlayerBlockPlacement(mc.thePlayer.inventory.getCurrentItem())
        val blockMent = C08PacketPlayerBlockPlacement(BlockPos(-1, -1, -1), 255, mc.thePlayer.inventory.getCurrentItem(), 0f, 0f, 0f)
        if(onGround && !mc.thePlayer.onGround) {
            return
        }
        if(sendC07 && event.eventState == EventState.PRE) {
            if(delay && msTimer.hasTimePassed(delayValue)) {
                mc.netHandler.addToSendQueue(digging)
            } else if(!delay) {
                mc.netHandler.addToSendQueue(digging)
            }
        }
        if(sendC08 && event.eventState == EventState.POST) {
            if(delay && msTimer.hasTimePassed(delayValue) && !watchDog) {
                mc.netHandler.addToSendQueue(blockPlace)
                msTimer.reset()
            } else if(!delay && !watchDog) {
                mc.netHandler.addToSendQueue(blockPlace)
            } else if(watchDog) {
                mc.netHandler.addToSendQueue(blockMent)
            }
        }
    }

    @EventTarget
    fun onPacket(event: PacketEvent) {
        val packet = event.packet
        val killAura = LiquidBounce.moduleManager[KillAura::class.java]!! as KillAura

        if (modeValue.get().equals("watchdog", true) && packet is S30PacketWindowItems && (mc.thePlayer.isUsingItem || mc.thePlayer.isBlocking)) {
            event.cancelEvent()
            if (debugValue.get())
                ClientUtils.displayChatMessage("detected reset item packet")
        }
        if (modeValue.get().equals("blink", true) && !(killAura.state && killAura.blockingStatus) && mc.thePlayer.itemInUse != null && mc.thePlayer.itemInUse.item != null) {
            val item = mc.thePlayer.itemInUse.item
            if (mc.thePlayer.isUsingItem && (item is ItemFood || item is ItemBucketMilk || item is ItemPotion) && (!ciucValue.get() || mc.thePlayer.itemInUseCount >= 1)) {
                if (packet is C04PacketPlayerPosition || packet is C06PacketPlayerPosLook) {
                    if (mc.thePlayer.positionUpdateTicks >= 20 && packetTriggerValue.get().equals("postrelease", true)) {
                        (packet as C03PacketPlayer).x = lastX
                        (packet as C03PacketPlayer).y = lastY
                        (packet as C03PacketPlayer).z = lastZ
                        (packet as C03PacketPlayer).onGround = lastOnGround
                        if (debugValue.get())
                            ClientUtils.displayChatMessage("pos update reached 20")
                    } else {
                        event.cancelEvent()
                        if (packetTriggerValue.get().equals("postrelease", true))
                            PacketUtils.sendPacketNoEvent(C03PacketPlayer(lastOnGround))
                        blinkPackets.add(packet as Packet<INetHandlerPlayServer>)
                        if (debugValue.get())
                            ClientUtils.displayChatMessage("packet player (movement) added at ${blinkPackets.size - 1}")
                    }
                } else if (packet is C05PacketPlayerLook) {
                    event.cancelEvent()
                    if (packetTriggerValue.get().equals("postrelease", true))
                        PacketUtils.sendPacketNoEvent(C03PacketPlayer(lastOnGround))
                    blinkPackets.add(packet as Packet<INetHandlerPlayServer>)
                    if (debugValue.get())
                        ClientUtils.displayChatMessage("packet player (rotation) added at ${blinkPackets.size - 1}")
                } else if (packet is C03PacketPlayer) {
                    if (packetTriggerValue.get().equals("prerelease", true) || packet.onGround != lastOnGround) {
                        event.cancelEvent()
                        blinkPackets.add(packet as Packet<INetHandlerPlayServer>)
                        if (debugValue.get())
                            ClientUtils.displayChatMessage("packet player (idle) added at ${blinkPackets.size - 1}")
                    }
                }
                if (packet is C0BPacketEntityAction) {
                    event.cancelEvent()
                    blinkPackets.add(packet as Packet<INetHandlerPlayServer>)
                    if (debugValue.get())
                        ClientUtils.displayChatMessage("packet action added at ${blinkPackets.size - 1}")
                }   
                if (packet is C07PacketPlayerDigging && packetTriggerValue.get().equals("prerelease", true)) {
                    if (blinkPackets.size > 0) {
                        blinkPackets.forEach {
                            PacketUtils.sendPacketNoEvent(it)
                        }
                        if (debugValue.get())
                            ClientUtils.displayChatMessage("sent ${blinkPackets.size} packets.")
                        blinkPackets.clear()
                    }
                }
            }
        }
    }

    @EventTarget
    fun onMotion(event: MotionEvent) {
        if (!MovementUtils.isMoving() && !modeValue.get().equals("blink", true))
            return

        val heldItem = mc.thePlayer.heldItem
        val killAura = LiquidBounce.moduleManager[KillAura::class.java]!! as KillAura

        when (modeValue.get().lowercase()) {
            "aac5" -> if (event.eventState == EventState.POST && (mc.thePlayer.isUsingItem || mc.thePlayer.isBlocking || killAura.blockingStatus)) {
                mc.netHandler.addToSendQueue(C08PacketPlayerBlockPlacement(BlockPos(-1, -1, -1), 255, mc.thePlayer.inventory.getCurrentItem(), 0f, 0f, 0f))
            }
            "watchdog" -> if (testValue.get() && (!killAura.state || !killAura.blockingStatus) 
                && event.eventState == EventState.PRE
                && mc.thePlayer.itemInUse != null && mc.thePlayer.itemInUse.item != null) {
                val item = mc.thePlayer.itemInUse.item
                if (mc.thePlayer.isUsingItem && (item is ItemFood || item is ItemBucketMilk || item is ItemPotion) && mc.thePlayer.getItemInUseCount() >= 1)
                    PacketUtils.sendPacketNoEvent(C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem))
            }
            "blink" -> {
                if (event.eventState == EventState.PRE && !mc.thePlayer.isUsingItem && !mc.thePlayer.isBlocking) {
                    lastX = event.x
                    lastY = event.y
                    lastZ = event.z
                    lastOnGround = event.onGround
                    if (blinkPackets.size > 0 && packetTriggerValue.get().equals("postrelease", true)) {
                        blinkPackets.forEach {
                            PacketUtils.sendPacketNoEvent(it)
                        }
                        if (debugValue.get())
                            ClientUtils.displayChatMessage("sent ${blinkPackets.size} packets.")
                        blinkPackets.clear()
                    }
                }
            }
            "experimental" -> {
                if ((mc.thePlayer.isUsingItem || mc.thePlayer.isBlocking) && timer.hasTimePassed(placeDelay)) {
                    mc.playerController.syncCurrentPlayItem()
                    mc.netHandler.addToSendQueue(C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN))
                    if (event.eventState == EventState.POST) {
                        placeDelay = 200L
                        if (fasterDelay) {
                            placeDelay = 100L
                            fasterDelay = false
                        } else
                            fasterDelay = true
                        timer.reset()
                    }
                }
            }
            else -> {
                if (!mc.thePlayer.isBlocking && !killAura.blockingStatus)
                    return
                when (modeValue.get().lowercase()) {
                    "aac" -> {
                        if (mc.thePlayer.ticksExisted % 3 == 0)
                            sendPacket(event, true, false, false, 0, false)
                        else
                            sendPacket(event, false, true, false, 0, false)
                    }
                    "ncp" -> sendPacket(event, true, true, false, 0, false)
                    "oldwatchdog" -> {
                        if (mc.thePlayer.ticksExisted % 2 == 0)
                            sendPacket(event, true, false, false, 50, true)
                        else
                            sendPacket(event, false, true, false, 0, true, true)
                    }
                    "oldhypixel" -> {
                        if (event.eventState == EventState.PRE)
                            mc.netHandler.addToSendQueue(C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos(-1, -1, -1), EnumFacing.DOWN))
                        else
                            mc.netHandler.addToSendQueue(C08PacketPlayerBlockPlacement(BlockPos(-1, -1, -1), 255, null, 0.0f, 0.0f, 0.0f))
                    }
                    "custom" -> sendPacket(event, customRelease.get(), customPlace.get(), customDelayValue.get() > 0, customDelayValue.get().toLong(), customOnGround.get())
                }
            }
        }
    }

    @EventTarget
    fun onSlowDown(event: SlowDownEvent) {
        val heldItem = mc.thePlayer.heldItem?.item

        event.forward = getMultiplier(heldItem, true)
        event.strafe = getMultiplier(heldItem, false)
    }

    private fun getMultiplier(item: Item?, isForward: Boolean) = when (item) {
        is ItemFood, is ItemPotion, is ItemBucketMilk -> {
            if (isForward) this.consumeForwardMultiplier.get() else this.consumeStrafeMultiplier.get()
        }
        is ItemSword -> {
            if (isForward) this.blockForwardMultiplier.get() else this.blockStrafeMultiplier.get()
        }
        is ItemBow -> {
            if (isForward) this.bowForwardMultiplier.get() else this.bowStrafeMultiplier.get()
        }
        else -> 0.2F
    }
}


// ===== File 2: 1ced46e7-97ea-4fda-a0b0-fc34d4e8547d.kt =====

/*
 * RinBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/rattermc/rinbounce69
 */
package net.ccbluex.liquidbounce.features.module.modules.movement

import net.ccbluex.liquidbounce.event.*
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.modules.combat.KillAura
import net.ccbluex.liquidbounce.utils.client.BlinkUtils
import net.ccbluex.liquidbounce.utils.client.PacketUtils.sendPacket
import net.ccbluex.liquidbounce.utils.extensions.isMoving
import net.ccbluex.liquidbounce.utils.inventory.InventoryUtils
import net.ccbluex.liquidbounce.utils.inventory.SilentHotbar
import net.ccbluex.liquidbounce.utils.movement.MovementUtils.hasMotion
import net.ccbluex.liquidbounce.utils.timing.TickTimer
import net.minecraft.item.*
import net.minecraft.network.handshake.client.C00Handshake
import net.minecraft.network.play.client.*
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action.DROP_ITEM
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action.RELEASE_USE_ITEM
import net.minecraft.network.play.server.S12PacketEntityVelocity
import net.minecraft.network.play.server.S27PacketExplosion
import net.minecraft.network.play.server.S2FPacketSetSlot
import net.minecraft.network.status.client.C00PacketServerQuery
import net.minecraft.network.status.client.C01PacketPing
import net.minecraft.network.status.server.S01PacketPong
import net.minecraft.util.BlockPos
import net.minecraft.util.EnumFacing

object NoSlow : Module("NoSlow", Category.MOVEMENT, gameDetecting = false) {

    private val swordMode by choices(
        "SwordMode",
        arrayOf("None", "NCP", "UpdatedNCP", "AAC5", "SwitchItem", "InvalidC08", "Blink", "OldGrim", "Intave14", "GrimAC", "Test"),
        "None"
    )

    private val reblinkTicks by int("ReblinkTicks", 10, 1..20) { swordMode == "Blink" }

    private val blockForwardMultiplier by float("BlockForwardMultiplier", 1f, 0.2F..1f)
    private val blockStrafeMultiplier by float("BlockStrafeMultiplier", 1f, 0.2F..1f)

    private val consumeMode by choices(
        "ConsumeMode",
        arrayOf("None", "UpdatedNCP", "Test", "AAC5", "SwitchItem", "InvalidC08", "Intave14", "Drop"),
        "None"
    )

    private val consumeForwardMultiplier by float("ConsumeForwardMultiplier", 1f, 0.2F..1f)
    private val consumeStrafeMultiplier by float("ConsumeStrafeMultiplier", 1f, 0.2F..1f)
    private val consumeFoodOnly by boolean(
        "ConsumeFood",
        true
    ) { consumeForwardMultiplier > 0.2F || consumeStrafeMultiplier > 0.2F }
    private val consumeDrinkOnly by boolean(
        "ConsumeDrink",
        true
    ) { consumeForwardMultiplier > 0.2F || consumeStrafeMultiplier > 0.2F }

    private val bowPacket by choices(
        "BowMode",
        arrayOf("None", "UpdatedNCP", "AAC5", "SwitchItem", "InvalidC08"),
        "None"
    )

    private val bowForwardMultiplier by float("BowForwardMultiplier", 1f, 0.2F..1f)
    private val bowStrafeMultiplier by float("BowStrafeMultiplier", 1f, 0.2F..1f)

    // Blocks
    val soulSand by boolean("SoulSand", true)
    val liquidPush by boolean("LiquidPush", true)

    private var shouldSwap = false
    private var shouldBlink = true
    private var shouldNoSlow = false

    private var hasDropped = false

    private val BlinkTimer = TickTimer()

    override fun onDisable() {
        shouldSwap = false
        shouldBlink = true
        BlinkTimer.reset()
        BlinkUtils.unblink()
    }

    val onMotion = handler<MotionEvent> { event ->
        val player = mc.thePlayer ?: return@handler
        val heldItem = player.heldItem ?: return@handler
        val isUsingItem = usingItemFunc()

        if (!hasMotion && !shouldSwap)
            return@handler

        if (isUsingItem || shouldSwap) {
            if (heldItem.item !is ItemSword && heldItem.item !is ItemBow && (consumeFoodOnly && heldItem.item is ItemFood ||
                        consumeDrinkOnly && (heldItem.item is ItemPotion || heldItem.item is ItemBucketMilk))
            ) {
                when (consumeMode.lowercase()) {
                    "aac5" ->
                        sendPacket(C08PacketPlayerBlockPlacement(BlockPos(-1, -1, -1), 255, heldItem, 0f, 0f, 0f))

                    "switchitem" ->
                        if (event.eventState == EventState.PRE) {
                            updateSlot()
                        }

                    "updatedncp" ->
                        if (event.eventState == EventState.PRE && shouldSwap) {
                            updateSlot()
                            sendPacket(C08PacketPlayerBlockPlacement(BlockPos.ORIGIN, 255, heldItem, 0f, 0f, 0f))
                            shouldSwap = false
                        }

                    "test" ->
                        if (event.eventState == EventState.PRE && shouldSwap) {
                            if (mc.thePlayer.isUsingItem) {
                                sendPacket(C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem % 8 + 1))
                                sendPacket(C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem))
                            }
                            sendPacket(C08PacketPlayerBlockPlacement(mc.thePlayer.inventoryContainer.getSlot(mc.thePlayer.inventory.currentItem + 36).stack))
                        }

                    "invalidc08" -> {
                        if (event.eventState == EventState.PRE) {
                            if (InventoryUtils.hasSpaceInInventory()) {
                                if (player.ticksExisted % 3 == 0)
                                    sendPacket(C08PacketPlayerBlockPlacement(BlockPos(-1, -1, -1), 1, null, 0f, 0f, 0f))
                            }
                        }
                    }

        // Credit: @LiquidBounce NextGen
                    "intave14" -> {
                        if (event.eventState == EventState.PRE) {
                            if (mc.thePlayer.isUsingItem) {
                                sendPacket(C07PacketPlayerDigging(RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.UP))
                            }
                            if (mc.thePlayer.itemInUseCount == 3) {
                                mc.thePlayer.stopUsingItem()
                                sendPacket(C07PacketPlayerDigging(RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.UP))
                            }
                        }
                    }
                }
            }
        }

        if (heldItem.item is ItemBow && (isUsingItem || shouldSwap)) {
            when (bowPacket.lowercase()) {
                "aac5" ->
                    sendPacket(C08PacketPlayerBlockPlacement(BlockPos(-1, -1, -1), 255, heldItem, 0f, 0f, 0f))

                "switchitem" ->
                    if (event.eventState == EventState.PRE) {
                        updateSlot()
                    }

                "updatedncp" ->
                    if (event.eventState == EventState.PRE && shouldSwap) {
                        updateSlot()
                        sendPacket(C08PacketPlayerBlockPlacement(BlockPos.ORIGIN, 255, heldItem, 0f, 0f, 0f))
                        shouldSwap = false
                    }

                "invalidc08" -> {
                    if (event.eventState == EventState.PRE) {
                        if (InventoryUtils.hasSpaceInInventory()) {
                            if (player.ticksExisted % 3 == 0)
                                sendPacket(C08PacketPlayerBlockPlacement(BlockPos(-1, -1, -1), 1, null, 0f, 0f, 0f))
                        }
                    }
                }
            }
        }

        if (heldItem.item is ItemSword && isUsingItem) {
            when (swordMode.lowercase()) {
                "ncp" ->
                    when (event.eventState) {
                        EventState.PRE -> sendPacket(
                            C07PacketPlayerDigging(RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN)
                        )

                        EventState.POST -> sendPacket(
                            C08PacketPlayerBlockPlacement(
                                BlockPos(-1, -1, -1), 255, heldItem, 0f, 0f, 0f
                            )
                        )

                        else -> return@handler
                    }

                "updatedncp" ->
                    if (event.eventState == EventState.POST) {
                        sendPacket(C08PacketPlayerBlockPlacement(BlockPos.ORIGIN, 255, heldItem, 0f, 0f, 0f))
                    }

                "aac5" ->
                    if (event.eventState == EventState.POST) {
                        sendPacket(
                            C08PacketPlayerBlockPlacement(BlockPos(-1, -1, -1), 255, player.heldItem, 0f, 0f, 0f)
                        )
                    }

                "switchitem" ->
                    if (event.eventState == EventState.PRE) {
                        updateSlot()
                    }

                "test" ->
                    if (event.eventState == EventState.PRE && shouldSwap) {
                        if (mc.thePlayer.isUsingItem) {
                            sendPacket(C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem % 8 + 1))
                            sendPacket(C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem))
                        }
                        sendPacket(C08PacketPlayerBlockPlacement(mc.thePlayer.inventoryContainer.getSlot(mc.thePlayer.inventory.currentItem + 36).stack))
                    }

                "invalidc08" -> {
                    if (event.eventState == EventState.PRE) {
                        if (InventoryUtils.hasSpaceInInventory()) {
                            if (player.ticksExisted % 3 == 0)
                                sendPacket(C08PacketPlayerBlockPlacement(BlockPos(-1, -1, -1), 1, null, 0f, 0f, 0f))
                        }
                    }
                }

                "oldgrim" -> {
                    if (event.eventState == EventState.PRE) {
                        sendPacket(C09PacketHeldItemChange((mc.thePlayer.inventory.currentItem % 8) + 1))
                        sendPacket(C07PacketPlayerDigging(RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN))
                        sendPacket(C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem))
                    } else {
                        sendPacket(C08PacketPlayerBlockPlacement(BlockPos(-1, -1, -1), 255, heldItem, 0f, 0f, 0f))
                    }
                }
        // Credit: @LiquidBounce NextGen
                "intave14" -> {
                    if (event.eventState == EventState.PRE) {
                        if (player.isMoving) {
                            sendPacket(C07PacketPlayerDigging(RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN))
                            val blockPos = BlockPos(-1, -1, -1)
                            sendPacket(C08PacketPlayerBlockPlacement(blockPos, 255, heldItem, 0f, 0f, 0f))
                        }
                    }
                }
        // Credit: @Kevin
                "grimac" -> {
                    if (event.eventState == EventState.PRE) {
                        sendPacket(C09PacketHeldItemChange((mc.thePlayer.inventory.currentItem + 1) % 9))
                    } else if (event.eventState == EventState.POST) {
                        sendPacket(C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem))
                    }
                }
            }
        }
    }

    val onPacket = handler<PacketEvent> { event ->
        val packet = event.packet
        val player = mc.thePlayer ?: return@handler

        if (event.isCancelled || shouldSwap)
            return@handler

        // Credit: @ManInMyVan
        // TODO: Not sure how to fix random grim simulation flag. (Seem to only happen in Loyisa).
        if (consumeMode == "Drop") {
            if (player.heldItem?.item !is ItemFood || !player.isMoving) {
                shouldNoSlow = false
                return@handler
            }

            val isUsingItem = packet is C08PacketPlayerBlockPlacement && packet.placedBlockDirection == 255

            if (!player.isUsingItem) {
                shouldNoSlow = false
                hasDropped = false
            }

            if (isUsingItem && !hasDropped) {
                sendPacket(C07PacketPlayerDigging(DROP_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN))
                shouldNoSlow = false
                hasDropped = true
            } else if (packet is S2FPacketSetSlot && player.isUsingItem) {
                if (packet.func_149175_c() != 0 || packet.func_149173_d() != SilentHotbar.currentSlot + 36) return@handler

                event.cancelEvent()
                shouldNoSlow = true

                player.itemInUse = packet.func_149174_e()
                if (!player.isUsingItem) player.itemInUseCount = 0
                player.inventory.mainInventory[SilentHotbar.currentSlot] = packet.func_149174_e()
            }
        }

        if (swordMode == "Blink") {
            when (packet) {
                is C00Handshake, is C00PacketServerQuery, is C01PacketPing, is C01PacketChatMessage, is S01PacketPong -> return@handler

                is C07PacketPlayerDigging, is C02PacketUseEntity, is C12PacketUpdateSign, is C19PacketResourcePackStatus -> {
                    BlinkTimer.update()
                    if (shouldBlink && BlinkTimer.hasTimePassed(reblinkTicks) && (BlinkUtils.packetsReceived.isNotEmpty() || BlinkUtils.packets.isNotEmpty())) {
                        BlinkUtils.unblink()
                        BlinkTimer.reset()
                        shouldBlink = false
                    } else if (!BlinkTimer.hasTimePassed(reblinkTicks)) {
                        shouldBlink = true
                    }
                    return@handler
                }

                // Flush on kb
                is S12PacketEntityVelocity -> {
                    if (mc.thePlayer.entityId == packet.entityID) {
                        BlinkUtils.unblink()
                        return@handler
                    }
                }

                // Flush on explosion
                is S27PacketExplosion -> {
                    if (packet.field_149153_g != 0f || packet.field_149152_f != 0f || packet.field_149159_h != 0f) {
                        BlinkUtils.unblink()
                        return@handler
                    }
                }

                is C03PacketPlayer -> {
                    if (swordMode == "Blink") {
                        if (player.isMoving) {
                            if (player.heldItem?.item is ItemSword && usingItemFunc()) {
                                if (shouldBlink)
                                    BlinkUtils.blink(packet, event)
                            } else {
                                shouldBlink = true
                                BlinkUtils.unblink()
                            }
                        }
                    }
                }
            }
        }

        when (packet) {
            is C08PacketPlayerBlockPlacement -> {
                if (packet.stack?.item != null && player.heldItem?.item != null && packet.stack.item == mc.thePlayer.heldItem?.item) {
                    if ((consumeMode == "UpdatedNCP" && (
                                packet.stack.item is ItemFood ||
                                        packet.stack.item is ItemPotion ||
                                        packet.stack.item is ItemBucketMilk)) ||
                        (bowPacket == "UpdatedNCP" && packet.stack.item is ItemBow)
                    ) {
                        shouldSwap = true
                    }
                }
            }
        }
    }

    val onSlowDown = handler<SlowDownEvent> { event ->
        val heldItem = mc.thePlayer.heldItem?.item

        if (heldItem !is ItemSword) {
            if (!consumeFoodOnly && heldItem is ItemFood ||
                !consumeDrinkOnly && (heldItem is ItemPotion || heldItem is ItemBucketMilk)
            ) {
                return@handler
            }

            if (consumeMode == "Drop" && !shouldNoSlow)
                return@handler
        }

        event.forward = getMultiplier(heldItem, true)
        event.strafe = getMultiplier(heldItem, false)
    }

    private fun getMultiplier(item: Item?, isForward: Boolean) = when (item) {
        is ItemFood, is ItemPotion, is ItemBucketMilk -> if (isForward) consumeForwardMultiplier else consumeStrafeMultiplier

        is ItemSword -> if (isForward) blockForwardMultiplier else blockStrafeMultiplier

        is ItemBow -> if (isForward) bowForwardMultiplier else bowStrafeMultiplier

        else -> 0.2F
    }

    fun isUNCPBlocking() =
        swordMode == "UpdatedNCP" && mc.gameSettings.keyBindUseItem.isKeyDown && (mc.thePlayer.heldItem?.item is ItemSword)

    fun usingItemFunc() =
        mc.thePlayer?.heldItem != null && (mc.thePlayer.isUsingItem || (mc.thePlayer.heldItem?.item is ItemSword && KillAura.blockStatus) || isUNCPBlocking())

    private fun updateSlot() {
        SilentHotbar.selectSlotSilently(this, (SilentHotbar.currentSlot + 1) % 9, immediate = true)
        SilentHotbar.resetSlot(this, true)
    }
}
