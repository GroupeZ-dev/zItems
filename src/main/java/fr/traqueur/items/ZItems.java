package fr.traqueur.items;

import fr.maxlego08.menu.api.ButtonManager;
import fr.maxlego08.menu.api.InventoryManager;
import fr.maxlego08.menu.api.loader.NoneLoader;
import fr.traqueur.commands.spigot.CommandManager;
import fr.traqueur.items.api.ItemsPlugin;
import fr.traqueur.items.api.Logger;
import fr.traqueur.items.api.annotations.AutoListener;
import fr.traqueur.items.api.effects.Effect;
import fr.traqueur.items.api.effects.EffectsDispatcher;
import fr.traqueur.items.api.items.Item;
import fr.traqueur.items.api.managers.DurabilityManager;
import fr.traqueur.items.api.managers.EffectsManager;
import fr.traqueur.items.api.managers.ItemsManager;
import fr.traqueur.items.items.ZDurabilityManager;
import fr.traqueur.items.api.registries.*;
import fr.traqueur.items.api.settings.Settings;
import fr.traqueur.items.api.settings.models.AttributeMergeStrategy;
import fr.traqueur.items.api.utils.MessageUtil;
import fr.traqueur.items.blocks.BlockTracker;
import fr.traqueur.items.blocks.BlockTrackerListener;
import fr.traqueur.items.blocks.ZItemsProvider;
import fr.traqueur.items.menu.ItemsListButton;
import fr.traqueur.items.menu.ZItemsBackButton;
import fr.traqueur.items.menu.applicator.ApplicatorButton;
import fr.traqueur.items.menu.applicator.ApplicatorOutputButton;
import fr.traqueur.items.commands.CommandsMessageHandler;
import fr.traqueur.items.commands.ZItemsCommand;
import fr.traqueur.items.commands.arguments.EffectArgument;
import fr.traqueur.items.commands.arguments.ItemArgument;
import fr.traqueur.items.effects.pipeline.entries.state.TickDispatcher;
import fr.traqueur.items.effects.engine.ZEffectsDispatcher;
import fr.traqueur.items.effects.engine.ZEffectsManager;
import fr.traqueur.items.effects.engine.ZEventsListener;
import fr.traqueur.items.effects.pipeline.PipelineSettings;
import fr.traqueur.items.integration.recipe.RecipesHook;
import fr.traqueur.items.menu.ApplicatorMenu;
import fr.traqueur.items.items.ZItemsManager;
import fr.traqueur.items.listeners.*;
import fr.traqueur.items.items.ZItemsItemProvider;
import fr.traqueur.items.infrastructure.support.ReflectionsCache;
import org.bukkit.event.Listener;
import org.reflections.Reflections;
import fr.traqueur.items.infrastructure.registry.*;
import fr.traqueur.items.serialization.Keys;
import fr.traqueur.items.persistence.ZEffectDataType;
import fr.traqueur.items.persistence.ZTrackedBlockDataType;
import fr.traqueur.items.infrastructure.settings.PluginSettings;
import fr.traqueur.items.infrastructure.settings.readers.*;
import fr.traqueur.items.integration.ShopProviders;
import fr.traqueur.recipes.api.RecipesAPI;
import fr.traqueur.recipes.api.hook.Hook;
import fr.traqueur.structura.api.Structura;
import fr.traqueur.structura.exceptions.StructuraException;
import fr.traqueur.structura.references.Reference;
import fr.traqueur.structura.references.ReferenceRegistry;
import fr.traqueur.structura.registries.CustomReaderRegistry;
import fr.traqueur.structura.registries.DefaultValueRegistry;
import fr.traqueur.structura.types.TypeToken;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.banner.PatternType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;

public class ZItems extends ItemsPlugin {

    private static final String CONFIG_FILE = "config.yml";
    private static final String MESSAGES_FILE = "messages.yml";
    public static final String ITEMS_FOLDER = "items";
    public static final String EFFECTS_FOLDER = "effects";

    private final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger("zItems");

    private RecipesAPI recipesManager;
    private EffectsDispatcher dispatcher;
    private TickDispatcher tickDispatcher;
    private InventoryManager inventoryManager;
    private ButtonManager buttonManager;

    @Override
    public void onLoad() {
        Logger.init(logger, false);
        Registry.register(HooksRegistry.class, new ZHooksRegistry());
        Registry.get(HooksRegistry.class).scanPackage(this, "fr.traqueur.items");
        Registry.get(HooksRegistry.class).loadAll();
    }

    @Override
    public void onEnable() {

        long enableTime = System.currentTimeMillis();

        this.saveDefaultConfig();
        this.injectReaders();

        PluginSettings settings = this.createSettings(CONFIG_FILE, PluginSettings.class);
        Logger.init(logger, settings.debug());

        Logger.info("<yellow>=== ENABLE START ===");
        Logger.info("<gray>Plugin Version V<red>{}", this.getDescription().getVersion());

        MessageUtil.initialize(this);
        ZEffectDataType.initialize();
        ZTrackedBlockDataType.initialize();
        Keys.initialize(this);

        this.recipesManager = new RecipesAPI(this, settings.debug());
        Hook.addHook(new RecipesHook(this));

        this.reloadConfig();

        // The built-in hooks (zShop, EconomyShopGUI, ShopGUIPlus) register themselves when their
        // plugin is present. Any other plugin may register its own ShopProvider at its enable —
        // zItems does not have to know it. Without any provider, only the sell effects
        // (SELL_STICK, AUTO_SELL) are affected: they find none at use time and sell nothing.
        // Nothing else in the plugin depends on a shop, so this is not a reason to disable it.
        if (ShopProviders.initialize()) {
            Logger.info("Shop provider <green>{} <reset>has been found.", ShopProviders.FOUND_PROVIDER.pluginName());
        } else {
            Logger.info("No built-in shop provider found — the sell effects wait for a plugin to register one.");
        }

        this.registerRegistries();

        Logger.info("Setting up event dispatching system...");
        this.dispatcher = new ZEffectsDispatcher();
        ZEventsListener eventsListener = new ZEventsListener(this.dispatcher);
        Logger.info("<green>Event dispatching system initialized successfully!");

        this.registerAutoListeners();
        this.registerListeners();

        EffectsManager effectsManager = this.registerManager(EffectsManager.class, new ZEffectsManager());
        ItemsManager itemsManager = this.registerManager(ItemsManager.class, new ZItemsManager());
        this.registerManager(DurabilityManager.class, new ZDurabilityManager());

        this.getServer().getPluginManager().registerEvents(new BlockTrackerListener(BlockTracker.get(), itemsManager, effectsManager), this);

        this.loadButtons();
        this.loadInventories();

        this.registerCommands(settings);


        Bukkit.getScheduler().runTask(this, () -> {
            this.populateRegistries();
            eventsListener.registerDynamicListeners(this);
            itemsManager.generateRecipesFromLoadedItems();
            effectsManager.loadRecipes();

            this.tickDispatcher = new TickDispatcher(this);
            this.tickDispatcher.start();
        });

        Logger.info("<yellow>=== ENABLE DONE <gray>(<gold>" + Math.abs(enableTime - System.currentTimeMillis()) + "ms<gray>) <yellow>===");
    }

    private void registerAutoListeners() {
        Reflections reflections = ReflectionsCache.getInstance().getOrCreate(this, "fr.traqueur.items");
        Set<Class<?>> candidates = reflections.getTypesAnnotatedWith(AutoListener.class);
        int count = 0;
        for (Class<?> clazz : candidates) {
            if (!Listener.class.isAssignableFrom(clazz)) continue;
            if (!VersionFilter.passes(clazz, clazz.getSimpleName())) continue;
            try {
                Listener listener = (Listener) clazz.getDeclaredConstructor().newInstance();
                this.getServer().getPluginManager().registerEvents(listener, this);
                count++;
                Logger.debug("Registered auto-listener: {}", clazz.getSimpleName());
            } catch (LinkageError e) {
                Logger.warning("Skipping auto-listener <yellow>{}<reset> — missing API: {}", clazz.getSimpleName(), e.getMessage());
            } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
                Logger.warning("Failed to instantiate auto-listener {}: {}", clazz.getSimpleName(), e.getMessage());
            }
        }
        Logger.info("Registered <green>{}<reset> auto-listener(s).", count);
    }

    private void registerListeners() {
        this.getServer().getPluginManager().registerEvents(new CommandsListener(), this);
        this.getServer().getPluginManager().registerEvents(new DisableEnchantsListener(), this);
        this.getServer().getPluginManager().registerEvents(new ItemRestrictionsListener(this), this);
        this.getServer().getPluginManager().registerEvents(new AnvilEffectFusionListener(this), this);
        this.getServer().getPluginManager().registerEvents(new SmithingTableListener(this), this);
        this.getServer().getPluginManager().registerEvents(new StripLogListener(), this);
    }

    private void populateRegistries() {
        Registry.get(HandlersRegistry.class).scanPackage(this, "fr.traqueur.items");
        Registry.get(EntryHandlersRegistry.class).scanPackage(this, "fr.traqueur.items");
        Registry.get(ExtractorsRegistry.class).scanPackage(this, "fr.traqueur.items");
        Registry.get(HooksRegistry.class).enableAll();
        Registry.get(EffectsRegistry.class).loadFromFolder();
        Registry.get(ItemsRegistry.class).loadFromFolder();
        Registry.get(CustomBlockProviderRegistry.class).register(this.getName().toLowerCase(), new ZItemsProvider());
        Registry.get(ItemProviderRegistry.class).register(this.getName().toLowerCase(), new ZItemsItemProvider());
        validatePipelineReferences();
    }

    /**
     * Warm-up pass: forces every PIPELINE effect's step Reference to resolve once, right
     * after everything is loaded, so a genuinely broken step id is still a loud startup
     * error instead of a silent failure the first time that pipeline actually dispatches.
     */
    private void validatePipelineReferences() {
        for (Effect effect : Registry.get(EffectsRegistry.class).getAll()) {
            if (!(effect.settings() instanceof PipelineSettings pipelineSettings)) {
                continue;
            }
            for (Reference<Effect> step : pipelineSettings.steps()) {
                try {
                    step.element();
                } catch (Exception e) {
                    Logger.severe("Pipeline <yellow>{}<reset> references unknown step <yellow>{}<reset>: {}",
                            e, effect.id(), step.key());
                }
            }
        }
    }

    private void registerRegistries() {
        Registry.register(LocationAccessRegistry.class, new ZLocationAccessRegistry());
        // Register custom block provider registry
        Registry.register(CustomBlockProviderRegistry.class, new ZCustomBlockProviderRegistry());
        // Register custom entity provider registry
        Registry.register(CustomEntityProviderRegistry.class, new ZCustomEntityProviderRegistry());
        // Register item provider registry
        Registry.register(ItemProviderRegistry.class, new ZItemProviderRegistry());
        // Register and scan effect handlers
        Registry.register(HandlersRegistry.class, new ZHandlersRegistry(this));
        // Register and scan entry handlers (pipeline trigger conditions)
        Registry.register(EntryHandlersRegistry.class, new ZEntryHandlersRegistry(this));
        // Register and load effects from files
        Registry.register(EffectsRegistry.class, new ZEffectsRegistry(this));
        // Register and load items from files
        Registry.register(ItemsRegistry.class, new ZItemsRegistry(this));
        // Register and scan extractors
        Registry.register(ExtractorsRegistry.class, new ZExtractorsRegistry(this));
        // Register applicators registry
        Registry.register(ApplicatorsRegistry.class, new ZApplicatorsRegistry());
    }

    private void registerCommands(PluginSettings settings) {
        CommandManager<@NotNull ItemsPlugin> commandManager = new CommandManager<>(this);
        commandManager.setLogger(new fr.traqueur.commands.api.logging.Logger() {
            @Override
            public void error(String s) {
                Logger.severe(s);
            }

            @Override
            public void info(String s) {
                Logger.info(s);
            }
        });
        commandManager.setDebug(settings.debug());
        commandManager.setMessageHandler(new CommandsMessageHandler());

        commandManager.registerConverter(Effect.class, new EffectArgument());
        commandManager.registerConverter(Item.class, new ItemArgument());

        commandManager.registerCommand(new ZItemsCommand(this));
    }

    private void loadButtons() {
        // Initialize zMenu ButtonManager
        var buttonProvider = getServer().getServicesManager().getRegistration(ButtonManager.class);
        if (buttonProvider == null) {
            Logger.severe("zMenu ButtonManager not found! Is zMenu installed?");
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        buttonManager = buttonProvider.getProvider();

        // Register custom buttons
        buttonManager.unregisters(this);
        buttonManager.register(new NoneLoader(this, ItemsListButton.class, "ZITEMS_ITEMS_LIST"));
        buttonManager.register(new NoneLoader(this, ZItemsBackButton.class, "ZITEMS_BACK"));
        buttonManager.register(new NoneLoader(this, ApplicatorButton.Input.class, "ZITEMS_EFFECT_APPLICATOR_INPUTS"));
        buttonManager.register(new NoneLoader(this, ApplicatorButton.BaseInput.class, "ZITEMS_EFFECT_APPLICATOR_BASE_INPUT"));
        buttonManager.register(new NoneLoader(this, ApplicatorButton.EffectInput.class, "ZITEMS_EFFECT_APPLICATOR_EFFECT_INPUT"));
        buttonManager.register(new NoneLoader(this, ApplicatorOutputButton.class, "ZITEMS_EFFECT_APPLICATOR_OUTPUT"));

        Logger.info("Registered <green>custom zMenu buttons<reset>!");
    }

    private void loadInventories() {
        // Initialize zMenu InventoryManager and ButtonManager
        var inventoryProvider = getServer().getServicesManager().getRegistration(InventoryManager.class);
        if (inventoryProvider == null) {
            Logger.severe("zMenu InventoryManager not found! Is zMenu installed?");
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.inventoryManager = inventoryProvider.getProvider();
        Logger.info("zMenu <green>InventoryManager <reset>initialized successfully!");

        if(this.inventoryManager != null && this.buttonManager != null) {
            try {
                this.inventoryManager.deleteInventories(this);
                this.inventoryManager.loadInventoryOrSaveResource(this, "inventories/items_list.yml");
                this.inventoryManager.loadInventoryOrSaveResource(this, "inventories/effect_applicator.yml", ApplicatorMenu.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void injectReaders() {
        CustomReaderRegistry.getInstance().register(EquipmentSlotGroup.class, new EquipmentSlotGroupReader());
        CustomReaderRegistry.getInstance().register(Attribute.class, new AttributeReader());
        CustomReaderRegistry.getInstance().register(Enchantment.class, new EnchantmentReader());
        CustomReaderRegistry.getInstance().register(new TypeToken<>() {}, new TagReader());
        CustomReaderRegistry.getInstance().register(Component.class, new ComponentReader());
        CustomReaderRegistry.getInstance().register(Effect.class, new EffectReader());
        ReferenceRegistry.getInstance().install(Effect.class, Effect::id, () -> Registry.get(EffectsRegistry.class).getAll());
        CustomReaderRegistry.getInstance().register(PotionEffectType.class, new PotionEffectTypeReader());
        CustomReaderRegistry.getInstance().register(PotionType.class, new PotionTypeReader());
        CustomReaderRegistry.getInstance().register(Color.class, new ColorReader());
        CustomReaderRegistry.getInstance().register(TrimMaterial.class, new TrimMaterialReader());
        CustomReaderRegistry.getInstance().register(TrimPattern.class, new TrimPatternReader());
        CustomReaderRegistry.getInstance().register(Sound.class, new SoundReader());
        CustomReaderRegistry.getInstance().register(PatternType.class, new PatternTypeReader());
        CustomReaderRegistry.getInstance().register(new TypeToken<>() {}, new DamageTypeReader());
        CustomReaderRegistry.getInstance().register(NamespacedKey.class, new NamespacedKeyReader());
        DefaultValueRegistry.getInstance().register(AttributeMergeStrategy.class, AttributeMergeStrategy.DefaultStrategy.class, AttributeMergeStrategy.DefaultStrategy::value);
    }

    @Override
    public void onDisable() {
        long disableTime = System.currentTimeMillis();
        Logger.info("<yellow>=== DISABLE START ===");
        Logger.info("<gray>Plugin Version V<red>{}", this.getDescription().getVersion());

        HooksRegistry hooksRegistry = Registry.get(HooksRegistry.class);
        if (hooksRegistry != null) {
            hooksRegistry.disableAll();
        }

        if (this.tickDispatcher != null) {
            this.tickDispatcher.stop();
        }

        BlockTracker.get().clearCache();

        MessageUtil.close();

        Logger.info("<yellow>=== DISABLE DONE <gray>(<gold>" + Math.abs(disableTime - System.currentTimeMillis()) + "ms<gray>) <yellow>===");
    }

    @Override
    public void saveDefaultConfig() {
        this.saveIfNotExits(CONFIG_FILE);
        this.saveIfNotExits(MESSAGES_FILE);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        PluginSettings settings = this.createSettings(CONFIG_FILE, PluginSettings.class);
        Logger.setDebug(settings.debug());
        try {
            Structura.loadEnum(this.getDataFolder().toPath().resolve(MESSAGES_FILE), Messages.class);
        } catch (StructuraException e) {
            this.logger.error("Failed to load messages configuration.", e);
        }

        EffectsRegistry effectsRegistry = Registry.get(EffectsRegistry.class);
        if (effectsRegistry != null) {
            effectsRegistry.loadFromFolder();
        }

        ItemsRegistry registry = Registry.get(ItemsRegistry.class);
        if (registry != null) {
            registry.loadFromFolder();
        }

        ItemsManager itemsManager = this.getManager(ItemsManager.class);
        if (itemsManager != null) {
            itemsManager.generateRecipesFromLoadedItems();
        }

        EffectsManager effectsManager = this.getManager(EffectsManager.class);
        if (effectsManager != null) {
            effectsManager.loadRecipes();
        }

        this.loadInventories();
    }

    private <T extends Settings> T createSettings(String path, Class<T> clazz) {
        File file = new File(this.getDataFolder(), path);
        if (!file.exists()) {
            throw new IllegalArgumentException("File " + path + " does not exist.");
        }
        T instance = Structura.load(file, clazz);
        Settings.register(clazz, instance);
        return instance;
    }

    private void saveIfNotExits(String fileName) {
        if (!this.getDataFolder().exists()) {
            this.getDataFolder().mkdirs();
        }
        File file = new File(this.getDataFolder(), fileName);
        if (!file.exists()) {
            this.saveResource(fileName, false);
        }
    }

    @Override
    public RecipesAPI getRecipesManager() {
        return recipesManager;
    }

    @Override
    public EffectsDispatcher getDispatcher() {
        return dispatcher;
    }

    public InventoryManager getInventoryManager() {
        return inventoryManager;
    }
}
