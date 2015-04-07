/*
 * Copyright (C) 2011-2015 Rinde van Lon, iMinds-DistriNet, KU Leuven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rinde.rinsim.core;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.measure.quantity.Duration;
import javax.measure.unit.SI;
import javax.measure.unit.Unit;

import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.rinde.rinsim.core.model.DependencyProvider;
import com.github.rinde.rinsim.core.model.Model;
import com.github.rinde.rinsim.core.model.Model.AbstractModel;
import com.github.rinde.rinsim.core.model.ModelBuilder;
import com.github.rinde.rinsim.core.model.ModelBuilder.AbstractModelBuilder;
import com.github.rinde.rinsim.core.model.ModelManager;
import com.github.rinde.rinsim.core.model.ModelProvider;
import com.github.rinde.rinsim.core.model.rand.RandomModel;
import com.github.rinde.rinsim.core.model.time.Clock;
import com.github.rinde.rinsim.core.model.time.TickListener;
import com.github.rinde.rinsim.core.model.time.TimeLapse;
import com.github.rinde.rinsim.core.model.time.TimeModel;
import com.github.rinde.rinsim.event.Event;
import com.github.rinde.rinsim.event.EventAPI;
import com.github.rinde.rinsim.event.EventDispatcher;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableCollection;

/**
 * Simulator is the core class of a simulation. It is responsible for managing
 * time which it does by periodically providing {@link TimeLapse} instances to
 * registered {@link TickListener}s. Further it provides methods to start and
 * stop simulations. The simulator also acts as a facade through which
 * {@link Model}s and objects can be added to the simulator.
 *
 * The configuration phase of the simulator looks as follows:
 * <ol>
 * <li>Use {@link Simulator#builder()} to register models and define other
 * global settings.</li>
 * <li>Obtain a {@link Simulator} instance via {@link Simulator.Builder#build()}.
 * <li>register objects using {@link #register(Object)}</li>
 * <li>start simulation by calling {@link #start()}</li>
 * </ol>
 *
 * @author Rinde van Lon
 * @author Bartosz Michalik
 */
public final class Simulator implements SimulatorAPI {

  /**
   * The logger of the simulator.
   */
  protected static final Logger LOGGER = LoggerFactory
    .getLogger(Simulator.class);

  /**
   * Enum that describes the possible types of events that the simulator can
   * dispatch.
   */
  public enum SimulatorEventType {
    /**
     * Indicates that the simulator has stopped.
     */
    STOPPED,

    /**
     * Indicates that the simulator has started.
     */
    STARTED
  }

  /**
   * Reference to dispatcher of simulator events, can be used by subclasses to
   * issue additional events.
   */
  protected final EventDispatcher dispatcher;

  /**
   * Model manager instance.
   */
  protected final ModelManager modelManager;

  private final Set<Object> toUnregister;
  private final RandomGenerator rand;
  private final Clock timeModel;

  @SuppressWarnings("unchecked")
  Simulator(Builder b) {
    toUnregister = new LinkedHashSet<>();
    dispatcher = new EventDispatcher(SimulatorEventType.values());
    rand = b.rng;

    final ModelManager.Builder mb = ModelManager.builder();
    mb.add(new SimulatorModelBuilder(this));
    for (final Object o : b.models) {
      if (o instanceof ModelBuilder<?>) {
        mb.add((ModelBuilder<?>) o);
      } else {
        mb.add((Supplier<? extends Model<?>>) o);
      }
    }
    mb.setDefaultProvider(RandomModel.builder(b.rng));
    mb.setDefaultProvider(TimeModel.supplier(b.tickLength, b.timeUnit));
    modelManager = mb.build();
    timeModel = modelManager.getModel(TimeModel.class);
  }

  /**
   * @param m The model.
   * @return False.
   * @deprecated To add a {@link Model} use {@link #builder()} instead.
   * @throws UnsupportedOperationException is always thrown.
   */
  @SuppressWarnings("static-method")
  @Deprecated
  public boolean register(Model<?> m) {
    throw new UnsupportedOperationException(
      "Models can be added via Simulator.builder().");
  }

  @Override
  public void register(Object obj) {
    LOGGER.info("{} - register({})", timeModel.getCurrentTime(), obj);
    modelManager.register(obj);
  }

  /**
   * {@inheritDoc} Unregistration from the models is delayed until all ticks are
   * processed.
   */
  @Override
  public void unregister(Object o) {
    if (o instanceof Model<?>) {
      throw new IllegalArgumentException("can not unregister a model");
    }
    toUnregister.add(o);
  }

  /**
   * Returns all models registered in the simulator.
   * @return immutable list of models.
   */
  public ImmutableCollection<Model<?>> getModels() {
    return modelManager.getModels();
  }

  /**
   * Returns the {@link ModelProvider} that has all registered models.
   * @return The model provider
   */
  public ModelProvider getModelProvider() {
    return modelManager;
  }

  @Override
  public long getCurrentTime() {
    return timeModel.getCurrentTime();
  }

  @Override
  public long getTimeStep() {
    return timeModel.getTimeStep();
  }

  /**
   * Adds a tick listener to the simulator.
   * @param listener The listener to add.
   */
  public void addTickListener(TickListener listener) {
    register(listener);
  }

  /**
   * Removes the listener specified.
   * @param listener The listener to remove
   */
  public void removeTickListener(TickListener listener) {
    unregister(listener);
  }

  /**
   * Start the simulation.
   */
  public void start() {
    if (!timeModel.isTicking()) {
      dispatcher.dispatchEvent(new Event(SimulatorEventType.STARTED, this));
    }
    timeModel.start();
    dispatcher.dispatchEvent(new Event(SimulatorEventType.STOPPED, this));
  }

  /**
   * Either starts or stops the simulation depending on the current state.
   */
  public void togglePlayPause() {
    if (!timeModel.isTicking()) {
      start();
    } else {
      stop();
    }
  }

  /**
   * Stops the simulation.
   */
  public void stop() {
    timeModel.stop();
  }

  /**
   * Advances the time a single tick.
   */
  public void tick() {
    timeModel.tick();
  }

  /**
   * @return true if simulator is playing, false otherwise.
   */
  public boolean isPlaying() {
    return timeModel.isTicking();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RandomGenerator getRandomGenerator() {
    return rand;
  }

  @Override
  public Unit<Duration> getTimeUnit() {
    return timeModel.getTimeUnit();
  }

  @Override
  public EventAPI getEventAPI() {
    return dispatcher.getPublicEventAPI();
  }

  /**
   * @return A new {@link Builder} for creating a {@link Simulator} instance.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for {@link Simulator}.
   * @author Rinde van Lon
   */
  public static class Builder {
    static final long DEFAULT_SEED = 123L;
    static final long DEFAULT_TICK_LENGTH = 1000L;

    RandomGenerator rng;
    Unit<Duration> timeUnit;
    long tickLength;
    List<Object> models;

    Builder() {
      rng = new MersenneTwister(DEFAULT_SEED);
      timeUnit = SI.MILLI(SI.SECOND);
      tickLength = DEFAULT_TICK_LENGTH;
      models = new ArrayList<>();
    }

    /**
     * Sets the random seed used in the {@link RandomGenerator} used in the
     * simulator. The default {@link RandomGenerator} is a
     * {@link MersenneTwister} with seed <code>123</code>.
     * @param seed The seed to use.
     * @return This, as per the builder pattern.
     */
    public Builder setRandomSeed(long seed) {
      rng.setSeed(seed);
      return this;
    }

    /**
     * Sets the {@link RandomGenerator} to use in the simulator. This overwrites
     * any previous calls made to {@link #setRandomSeed(long)}. The default
     * {@link RandomGenerator} is a {@link MersenneTwister} with seed
     * <code>123</code>.
     * @param randomGenerator The generator to set.
     * @return This, as per the builder pattern.
     */
    public Builder setRandomGenerator(RandomGenerator randomGenerator) {
      rng = randomGenerator;
      return this;
    }

    /**
     * Sets the time unit to use in the simulator. The default time unit is
     * milliseconds.
     * @param unit The time unit to use.
     * @return This, as per the builder pattern.
     */
    public Builder setTimeUnit(Unit<Duration> unit) {
      timeUnit = unit;
      return this;
    }

    /**
     * Sets the length of a single tick in the simulator. The default tick
     * length is <code>1000</code>.
     * @param length The tick length to set.
     * @return This, as per the builder pattern.
     */
    public Builder setTickLength(long length) {
      checkArgument(length > 0,
        "Tick length must be strictly positive but is %s.", length);
      tickLength = length;
      return this;
    }

    /**
     * Adds the specified {@link Supplier} to the simulator. The
     * {@link Supplier} will be used to obtain a {@link Model} instance that
     * will be added to the simulator.
     * @param supplier The supplier to add.
     * @return This, as per the builder pattern.
     */
    public Builder addModel(Supplier<? extends Model<?>> supplier) {
      models.add(supplier);
      return this;
    }

    /**
     * Adds the specified {@link ModelBuilder} to the simulator. The
     * {@link ModelBuilder} will be used to obtain a {@link Model} instance that
     * will be added to the simulator.
     * @param builder The builder to add.
     * @return This, as per the builder pattern.
     */
    public Builder addModel(ModelBuilder<?> builder) {
      models.add(builder);
      return this;
    }

    /**
     * Builds the simulator, at least one {@link Model} must have been added.
     * @return A new {@link Simulator} instance.
     */
    public Simulator build() {
      return new Simulator(this);
    }
  }

  static class SimulatorModelBuilder extends
    AbstractModelBuilder<SimulatorUser> {

    final Simulator simulator;

    SimulatorModelBuilder(Simulator sim) {
      super(SimulatorAPI.class);
      simulator = sim;
    }

    @Override
    public Model<SimulatorUser> build(DependencyProvider dependencyProvider) {
      return new SimulatorModel(simulator);
    }

  }

  static class SimulatorModel extends AbstractModel<SimulatorUser> {
    final Simulator simulator;

    SimulatorModel(Simulator sim) {
      simulator = sim;
    }

    @Override
    public boolean register(SimulatorUser element) {
      element.setSimulator(simulator);
      return true;
    }

    @Override
    public boolean unregister(SimulatorUser element) {
      return true;
    }
  }
}
