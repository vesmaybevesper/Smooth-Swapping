package dev.shwg.smoothswapping.mixin;

import dev.shwg.smoothswapping.ItemStackAccessor;
import dev.shwg.smoothswapping.SmoothSwapping;
import dev.shwg.smoothswapping.SwapUtil;
import dev.shwg.smoothswapping.config.CatmullRomWidget;
import dev.shwg.smoothswapping.config.Config;
import dev.shwg.smoothswapping.config.ConfigManager;
import dev.shwg.smoothswapping.config.ConfigScreen;
import dev.shwg.smoothswapping.swaps.InventorySwap;
import dev.shwg.smoothswapping.swaps.ItemToCursorInventorySwap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

import static dev.shwg.smoothswapping.SmoothSwapping.ASSUME_CURSOR_STACK_SLOT_INDEX;
import static dev.shwg.smoothswapping.SwapUtil.swapListIndexOf;

@Mixin(DrawContext.class)
public abstract class DrawContextMixin {

    @Final
    @Shadow
    private Matrix3x2fStack matrices;
    @Final
    @Shadow
    private MinecraftClient client;
    @Shadow
    public abstract void drawItem(ItemStack item, int x, int y);
    @Shadow public abstract void drawStackOverlay(TextRenderer textRenderer, ItemStack stack, int x, int y, @Nullable String countOverride);

    @Inject(method = "drawItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
    public void onItemDraw(LivingEntity entity, World world, ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
        if (smooth_Swapping$isHotbar() && !(client.currentScreen instanceof ConfigScreen)) return;

        if (((ItemStackAccessor) (Object) stack).smooth_Swapping$isSwapStack()) return;

        try {
            smooth_Swapping$doSwap(stack, x, y, ci);
        } catch (Exception e) {
            SwapUtil.reset();
        }
    }

    @Unique
    private void smooth_Swapping$doSwap(ItemStack stack, int x, int y, CallbackInfo cbi) throws Error {
        int index = SwapUtil.getSlotIndex(stack);

        if (SmoothSwapping.swaps.containsKey(index)) {
            //Get all swaps for one slot
            List<InventorySwap> swapList = SmoothSwapping.swaps.get(index);

            boolean renderDestinationSlot = true;

            //render all swaps for one slot
            for (int i = 0; i < swapList.size(); i++) {
                InventorySwap swap = swapList.get(i);

                swap.setRenderDestinationSlot(swap.isChecked());

                if (!swap.renderDestinationSlot()) {
                    renderDestinationSlot = false;
                }

                //LOGGER.info("render i2i swap, stack hash: " + stack.hashCode());
                smooth_Swapping$renderSwap(swap, x, y, swap.getSwapItem());

                if (SwapUtil.hasArrived(swap)) {
                    SwapUtil.setRenderToTrue(swapList);
                    swapList.remove(swap);
                }
            }

            //whether the destination slot should be rendered
            if (renderDestinationSlot) {
                drawItem(stack.copy(), x, y);
            }
            if (swapList.isEmpty())
                SmoothSwapping.swaps.remove(index);

            cbi.cancel();
        } else if (SmoothSwapping.swaps.containsKey(ASSUME_CURSOR_STACK_SLOT_INDEX)) {
            List<InventorySwap> swapList = SmoothSwapping.swaps
                    .get(ASSUME_CURSOR_STACK_SLOT_INDEX)
                    .stream().filter(swap -> !((ItemToCursorInventorySwap) swap).isArrived())
                    .toList();

            if (!swapList.isEmpty()) {
                if (swapListIndexOf(swapList, (swap) -> ((ItemToCursorInventorySwap) swap).getCopiedStackHash() == stack.hashCode()) == -1) {
                    ClientPlayerEntity player = MinecraftClient.getInstance().player;
                    ScreenHandler handler = null;
                    if (player != null) handler = player.currentScreenHandler;
                    //LOGGER.info("cursor stack hash: " + handler.getCursorStack().hashCode());

                    for (InventorySwap inventorySwap : swapList) { // assign initial renders
                        ItemToCursorInventorySwap swap = (ItemToCursorInventorySwap) inventorySwap;

                        if (!swap.isStartedRender()) {
                            //LOGGER.info("i2c start render " + swap.getSwapItem() +  ", hash=" + swap.getSwapItem().hashCode());
                            swap.setStartedRender(true);
                        } else if (!swap.isArrived()) {
                            if (handler != null) {
                                DefaultedList<ItemStack> inventoryStacks = handler.getStacks();
                                //LOGGER.info("render stack: [" + stack +  ", " + stack.hashCode() + "], inventory stacks:" + sb);
                                //LOGGER.info("target stack hash: " + swap.getTargetStackHash() + ", cursor stack hash: " + handler.getCursorStack().hashCode());
                                if (swap.getTargetStackHash() == -1 || handler.getCursorStack().hashCode() == swap.getTargetStackHash()) {
                                    if (!inventoryStacks.contains(stack)) { // now rendering cursor stack from parent
                                        ItemStack copiedStack = swap.getSwapItem().copy();
                                        swap.setCopiedStackHash(copiedStack.hashCode());
                                        if (swap.getTargetStackHash() == -1)
                                            swap.setTargetStackHash(stack.hashCode());
                                        //LOGGER.info("i2c insert render on " + stack + " to render " + swap.getSwapItem() + ", hash=" + swap.getSwapItem().hashCode());
                                        smooth_Swapping$renderSwap(swap, x, y, copiedStack);

                                        if (SwapUtil.hasArrived(swap)) swap.setArrived(true);
                                    }
                                } else {
                                    swap.setArrived(true);
                                    //LOGGER.info("cursor stack has changed, enforcing to arrive");
                                }
                            }
                        }
                    }
                } /*else LOGGER.info("i2c real render: " + stack + ", hash=" + stack.hashCode());*/
            }

            if (swapList.stream().allMatch(swap -> ((ItemToCursorInventorySwap) swap).isArrived()))
                SmoothSwapping.swaps.remove(ASSUME_CURSOR_STACK_SLOT_INDEX);
        }
    }

    @Unique
    private void smooth_Swapping$renderSwap(InventorySwap swap, int x, int y, ItemStack copiedStack) {
        float lastFrameDuration = client.getRenderTickCounter().getDynamicDeltaTicks();
        Config config = ConfigManager.getConfig();

        double swapX = swap.getX();
        double swapY = swap.getY();
        double angle = swap.getAngle();

        double progress = 1D - SwapUtil.map(Math.hypot(swapX, swapY), 0, swap.getDistance(), 1D, 0D);

        List<CatmullRomWidget.CatmullRomSpline> splines = config.getSplines();

        double ease = CatmullRomWidget.getProgress(progress, splines);

        double renderX = -swap.getStartX() - Math.cos(angle) * swap.getDistance() * ease;
        double renderY = swap.getStartY() + Math.sin(angle) * swap.getDistance() * ease;

        matrices.pushMatrix();
        matrices.translate((float) renderX, (float) -renderY);



        double speed = swap.getDistance() / 10 * config.getAnimationSpeedFormatted();

        swap.setX(swapX + lastFrameDuration * speed * Math.cos(angle));
        swap.setY(swapY + lastFrameDuration * speed * Math.sin(angle));
        drawItem(copiedStack, x, y);
        matrices.popMatrix();
    }

    @Inject(method = "drawStackOverlay(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    public void onDrawItemInSlot(TextRenderer textRenderer, ItemStack stack, int x, int y, String countOverride, CallbackInfo cbi) {
        if (smooth_Swapping$isHotbar() && !(client.currentScreen instanceof ConfigScreen)) return;

        if (((ItemStackAccessor) (Object) stack).smooth_Swapping$isSwapStack()) return;

        try {
            smooth_Swapping$doOverlayRender(stack, x, y, cbi);
        } catch (Exception e) {
            SwapUtil.reset();
        }
    }

    @Unique
    private void smooth_Swapping$doOverlayRender(ItemStack stack, int x, int y, CallbackInfo cbi) throws StackOverflowError {
        int index = SwapUtil.getSlotIndex(stack);

        if (SmoothSwapping.swaps.containsKey(index)) {
            List<InventorySwap> swapList = SmoothSwapping.swaps.get(index);
            Config config = ConfigManager.getConfig();
            int stackCount = stack.getCount();
            boolean renderToSlot = true;

            for (InventorySwap swap : swapList) {
                if (!ItemStack.areItemsEqual(stack, swap.getSwapItem())) {
                    SmoothSwapping.swaps.remove(index);
                    return;
                }

                stackCount -= swap.getAmount();
                if (!swap.renderDestinationSlot()) {
                    renderToSlot = false;
                }

                if (swap.getAmount() > 1 || stack.isItemBarVisible()) {
                    String amount = String.valueOf(swap.getAmount());
                    double swapX = swap.getX();
                    double swapY = swap.getY();
                    double angle = swap.getAngle();

                    double progress = 1D - SwapUtil.map(Math.hypot(swapX, swapY), 0, swap.getDistance(), 1D, 0D);

                    List<CatmullRomWidget.CatmullRomSpline> splines = config.getSplines();

                    double ease = CatmullRomWidget.getProgress(progress, splines);

                    double renderX = -swap.getStartX() - (Math.cos(angle) * swap.getDistance() * ease);
                    double renderY = swap.getStartY() + (Math.sin(angle) * swap.getDistance() * ease);

                    matrices.pushMatrix();
                    matrices.translate((float) renderX, (float) -renderY);

                    if (stack.isItemBarVisible())
                        drawStackOverlay(client.textRenderer, stack.copy(), x, y, null);
                    else
                        drawStackOverlay(client.textRenderer, stack.copy(), x, y, amount);

                    matrices.popMatrix();
                }

            }

            if (renderToSlot && stackCount > 1) {
                drawStackOverlay(client.textRenderer, stack.copy(), x, y, String.valueOf(stackCount));
            }
            cbi.cancel();
        }
    }


    @Unique
    private boolean smooth_Swapping$isHotbar() {
        Matrix3x2f curr = matrices;
        float xTranslation = curr.m20;
        return Math.round(xTranslation) <= 0;
    }
}
