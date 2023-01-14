package appeng.crafting.v2;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.crafting.MECraftingInventory;
import appeng.crafting.v2.resolvers.CraftingTask;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.util.item.OreListMultiMap;
import com.google.common.collect.ClassToInstanceMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.MutableClassToInstanceMap;
import java.util.*;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import net.minecraft.world.World;

/**
 * A bundle of state for the crafting operation like the ME grid, who requested crafting, etc.
 */
public final class CraftingContext {
    public final World world;
    public final IGrid meGrid;
    public final ICraftingGrid craftingGrid;
    public BaseActionSource actionSource;

    /**
     * A working copy of the AE system's item list used for modelling what happens as crafting requests get resolved.
     * Only extract, inject into {@link CraftingContext#byproductsInventory}.
     */
    public final MECraftingInventory itemModel;
    /**
     * An initially blank inventory for keeping all crafting byproduct outputs in.
     * Extract from here before extracting from {@link CraftingContext#itemModel}
     */
    public final MECraftingInventory byproductsInventory;
    /**
     * A cache of how many items were present at the beginning of the crafting request, do not modify
     */
    public final MECraftingInventory availableCache;

    public boolean wasSimulated = false;

    public static final class RequestInProcessing<StackType extends IAEStack<StackType>> {
        public final CraftingRequest<StackType> request;
        /**
         * Ordered by priority
         */
        public final Deque<CraftingTask> resolvers = new ArrayDeque<>(4);

        public RequestInProcessing(CraftingRequest<StackType> request) {
            this.request = request;
        }
    }

    private final List<RequestInProcessing<?>> liveRequests = new ArrayList<>(32);
    private final List<CraftingTask> resolvedTasks = new ArrayList<>();
    private final ArrayDeque<CraftingTask> tasksToProcess = new ArrayDeque<>(64);
    private boolean doingWork = false;
    // State at the point when the last task executed.
    private CraftingTask.State finishedState = CraftingTask.State.FAILURE;
    private final ImmutableMap<IAEItemStack, ImmutableList<ICraftingPatternDetails>> availablePatterns;
    private final Map<IAEItemStack, List<ICraftingPatternDetails>> precisePatternCache = new HashMap<>();
    private final OreListMultiMap<ICraftingPatternDetails> fuzzyPatternCache = new OreListMultiMap<>();
    private final ClassToInstanceMap<Object> userCaches = MutableClassToInstanceMap.create();

    public CraftingContext(@Nonnull World world, @Nonnull IGrid meGrid, @Nonnull BaseActionSource actionSource) {
        this.world = world;
        this.meGrid = meGrid;
        this.craftingGrid = meGrid.getCache(ICraftingGrid.class);
        this.actionSource = actionSource;
        final IStorageGrid sg = meGrid.getCache(IStorageGrid.class);
        this.itemModel = new MECraftingInventory(sg.getItemInventory(), this.actionSource, true, false, true);
        this.byproductsInventory = new MECraftingInventory();
        this.availableCache = new MECraftingInventory(sg.getItemInventory(), this.actionSource, false, false, false);
        this.availablePatterns = craftingGrid.getCraftingPatterns();
    }

    /**
     * Can be used for custom caching in plugins
     */
    public <T> T getUserCache(Class<T> cacheType, Supplier<T> constructor) {
        // Can't use compute with generic types safely here
        T instance = userCaches.getInstance(cacheType);
        if (instance == null) {
            instance = constructor.get();
            userCaches.putInstance(cacheType, instance);
        }
        return instance;
    }

    public void addRequest(@Nonnull CraftingRequest<?> request) {
        if (doingWork) {
            throw new IllegalStateException(
                    "Trying to add requests while inside a CraftingTask handler, return requests in the StepOutput instead");
        }
        final RequestInProcessing<?> processing = new RequestInProcessing<>(request);
        processing.resolvers.addAll(CraftingCalculations.tryResolveCraftingRequest(request, this));
        liveRequests.add(processing);
        if (processing.resolvers.isEmpty()) {
            throw new IllegalStateException("No resolvers available for request " + request.toString());
        }
        queueNextTaskOf(processing, true);
    }

    public List<ICraftingPatternDetails> getPrecisePatternsFor(@Nonnull IAEItemStack stack) {
        return precisePatternCache.compute(stack, (key, value) -> {
            if (value == null) {
                return availablePatterns.getOrDefault(stack, ImmutableList.of());
            } else {
                return value;
            }
        });
    }

    public List<ICraftingPatternDetails> getFuzzyPatternsFor(@Nonnull IAEItemStack stack) {
        if (!fuzzyPatternCache.isPopulated()) {
            for (final ImmutableList<ICraftingPatternDetails> patternSet : availablePatterns.values()) {
                for (final ICraftingPatternDetails pattern : patternSet) {
                    if (pattern.canBeSubstitute()) {
                        for (final IAEItemStack output : pattern.getOutputs()) {
                            fuzzyPatternCache.put(output.copy(), pattern);
                        }
                    }
                }
            }
            fuzzyPatternCache.freeze();
        }
        return fuzzyPatternCache.get(stack);
    }

    /**
     * Does one unit of work towards solving the crafting problem.
     *
     * @return Is more work needed?
     */
    public CraftingTask.State doWork() {
        if (tasksToProcess.isEmpty()) {
            return finishedState;
        }
        final CraftingTask frontTask = tasksToProcess.getFirst();
        if (frontTask.getState() == CraftingTask.State.SUCCESS || frontTask.getState() == CraftingTask.State.FAILURE) {
            resolvedTasks.add(frontTask);
            tasksToProcess.removeFirst();
            return CraftingTask.State.NEEDS_MORE_WORK;
        }
        doingWork = true;
        CraftingTask.StepOutput out = frontTask.calculateOneStep(this);
        CraftingTask.State newState = frontTask.getState();
        doingWork = false;
        if (out.extraInputsRequired.size() > 0) {
            out.extraInputsRequired.forEach(this::addRequest);
        } else if (newState == CraftingTask.State.SUCCESS) {
            if (tasksToProcess.getFirst() != frontTask) {
                throw new IllegalStateException("A crafting task got added to the queue without requesting more work.");
            }
            resolvedTasks.add(frontTask);
            tasksToProcess.removeFirst();
            finishedState = CraftingTask.State.SUCCESS;
        } else if (newState == CraftingTask.State.FAILURE) {
            tasksToProcess.clear();
            finishedState = CraftingTask.State.FAILURE;
            return CraftingTask.State.FAILURE;
        }
        return tasksToProcess.isEmpty() ? CraftingTask.State.SUCCESS : CraftingTask.State.NEEDS_MORE_WORK;
    }

    /**
     * Gets the list of tasks that have finished executing, sorted topologically (dependencies before the tasks that require them)
     * @return An unmodifiable list of resolved tasks.
     */
    public List<CraftingTask> getResolvedTasks() {
        return Collections.unmodifiableList(resolvedTasks);
    }

    /**
     * Gets all requests that have been added to the context.
     * @return An unmodifiable list of the requests.
     */
    public List<RequestInProcessing<?>> getLiveRequests() {
        return Collections.unmodifiableList(liveRequests);
    }

    /**
     * @return If a task was added
     */
    private boolean queueNextTaskOf(RequestInProcessing<?> request, boolean addResolverTask) {
        if (request.request.remainingToProcess <= 0 || request.resolvers.isEmpty()) {
            return false;
        }
        CraftingTask nextResolver = request.resolvers.removeFirst();
        if (addResolverTask && !request.resolvers.isEmpty()) {
            tasksToProcess.addFirst(new CheckOtherResolversTask(request));
        }
        tasksToProcess.addFirst(nextResolver);
        return true;
    }

    /**
     * A task to call queueNextTaskOf after a resolver gets computed to check if more resolving is needed for the same request-in-processing.
     */
    private final class CheckOtherResolversTask extends CraftingTask {
        private final RequestInProcessing<?> myRequest;

        public CheckOtherResolversTask(RequestInProcessing<?> myRequest) {
            super(0); // priority doesn't matter as this task is never a resolver output
            this.myRequest = myRequest;
        }

        @Override
        public StepOutput calculateOneStep(CraftingContext context) {
            final boolean needsMoreWork = queueNextTaskOf(myRequest, false);
            if (needsMoreWork) {
                this.state = State.NEEDS_MORE_WORK;
            } else if (myRequest.request.remainingToProcess <= 0) {
                this.state = State.SUCCESS;
            } else {
                this.state = State.FAILURE;
            }
            return new StepOutput();
        }

        @Override
        public long partialRefund(CraftingContext context, long amount) {
            // no-op, does not produce/consume any stacks
            return 0;
        }

        @Override
        public void fullRefund(CraftingContext context) {
            // no-op
        }

        @Override
        public void populatePlan(IItemList<IAEItemStack> targetPlan) {
            // no-op
        }

        @Override
        public void startOnCpu(
                CraftingContext context, CraftingCPUCluster cpuCluster, MECraftingInventory craftingInv) {
            // no-op
        }

        @Override
        public String toString() {
            return "CheckOtherResolversTask{" + "myRequest="
                    + myRequest + ", priority="
                    + priority + ", state="
                    + state + '}';
        }
    }
}
