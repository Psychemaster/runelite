/*
 * Copyright (c) 2019, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.mining;

import com.google.inject.Provides;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.HintArrowType;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.xptracker.XpTrackerPlugin;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.RSTimeUnit;

@PluginDescriptor(
	name = "Mining",
	description = "Show mining statistics and ore respawn timers",
	tags = {"overlay", "skilling", "timers"},
	enabledByDefault = false
)
@PluginDependency(XpTrackerPlugin.class)
@Slf4j
public class MiningPlugin extends Plugin
{
	private static final Pattern MINING_PATTERN = Pattern.compile(
		"You swing your pick at the " +
			"(?:rock|star)" +
			"(?:\\.|!)");

	private static final int DAEYALT_ESSENCE_MINE_REGION = 14744;

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MiningOverlay overlay;

	@Inject
	private MiningRocksOverlay rocksOverlay;

	@Inject
	private MiningConfig config;

	@Getter
	@Nullable
	@Setter(AccessLevel.PACKAGE)
	private MiningSession session;

	@Getter(AccessLevel.PACKAGE)
	private final List<RockRespawn> respawns = new ArrayList<>();

	@Getter
	private boolean isMining;

	@Getter(AccessLevel.PACKAGE)
	private Instant lastAnimationChange;

	@Getter(AccessLevel.PACKAGE)
	private int lastActionAnimationId;

	@Provides
	MiningConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MiningConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(rocksOverlay);
	}

	@Override
	protected void shutDown() throws Exception
	{
		session = null;
		isMining = false;
		overlayManager.remove(overlay);
		overlayManager.remove(rocksOverlay);
		respawns.forEach(respawn -> clearHintArrowAt(respawn.getWorldPoint()));
		respawns.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.HOPPING)
		{
			respawns.clear();
		}
	}

	@Subscribe
	public void onAnimationChanged(final AnimationChanged event)
	{
		Player local = client.getLocalPlayer();

		if (event.getActor() != local)
		{
			return;
		}
		lastAnimationChange = Instant.now();

		int animId = local.getAnimation();
		if (animId != -1)
		{
			lastActionAnimationId = animId;
		}

		if (animId == AnimationID.ARCEUUS_CHISEL_ESSENCE)
		{
			// Can't use chat messages to start mining session on Dense Essence as they don't have a chat message when mined,
			// so we track the session here instead.
			if (session == null)
			{
				session = new MiningSession();
			}

			session.setLastMined();
		}
		else
		{
			isMining |= MiningAnimation.MINING_ANIMATIONS.contains(animId);
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		clearExpiredRespawns();

		if (session == null || session.getLastMined() == null)
		{
			return;
		}

		if (isMining && MiningAnimation.MINING_ANIMATIONS.contains(client.getLocalPlayer().getAnimation()))
		{
			session.setLastMined();
			return;
		}

		Duration statTimeout = Duration.ofMinutes(config.statTimeout());
		Duration sinceMined = Duration.between(session.getLastMined(), Instant.now());

		if (sinceMined.compareTo(statTimeout) >= 0)
		{
			resetSession();
		}
	}

	/**
	 * Clears expired respawns and removes the hint arrow from expired Daeyalt essence rocks.
	 */
	private void clearExpiredRespawns()
	{
		respawns.removeIf(rockRespawn ->
		{
			final boolean expired = rockRespawn.isExpired();

			if (expired && rockRespawn.getRock() == Rock.DAEYALT_ESSENCE)
			{
				clearHintArrowAt(rockRespawn.getWorldPoint());
			}

			return expired;
		});
	}

	public void resetSession()
	{
		session = null;
		isMining = false;
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		final GameObject object = event.getGameObject();

		if (object.getId() == ObjectID.DAEYALT_STONE_TOP_ACTIVE)
		{
			final WorldPoint point = object.getWorldLocation();
			respawns.removeIf(rockRespawn -> rockRespawn.getWorldPoint().equals(point));
			clearHintArrowAt(point);
		}
	}

	private void clearHintArrowAt(WorldPoint worldPoint)
	{
		if (client.getHintArrowType() == HintArrowType.COORDINATE && client.getHintArrowPoint().equals(worldPoint))
		{
			client.clearHintArrow();
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		GameObject object = event.getGameObject();

		// Inverse timer to track daeyalt essence active duration
		if (object.getId() == ObjectID.DAEYALT_STONE_TOP_ACTIVE && client.getLocalPlayer().getWorldLocation().getRegionID() == DAEYALT_ESSENCE_MINE_REGION)
		{
			RockRespawn rockRespawn = new RockRespawn(Rock.DAEYALT_ESSENCE, object.getWorldLocation(), Instant.now(),
				(int) Duration.of(MiningRocksOverlay.DAEYALT_MAX_RESPAWN_TIME, RSTimeUnit.GAME_TICKS).toMillis(), Rock.DAEYALT_ESSENCE.getZOffset());
			respawns.add(rockRespawn);
			client.setHintArrow(object.getWorldLocation());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() == ChatMessageType.SPAM || event.getType() == ChatMessageType.GAMEMESSAGE)
		{
			if (MINING_PATTERN.matcher(event.getMessage()).matches())
			{
				if (session == null)
				{
					session = new MiningSession();
				}

				session.setLastMined();
			}
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired scriptPreFired)
	{
		if (scriptPreFired.getScriptId() == ScriptID.ADD_OVERLAYTIMER_LOC)
		{
			var args = scriptPreFired.getScriptEvent().getArguments();
			int locCoord = (int) args[1];
			int locId = (int) args[2];
			int ticks = (int) args[5];

			switch (locId)
			{
				case ObjectID.MOTHERLODE_DEPLETED_SINGLE: // Depleted motherlode vein
				case ObjectID.MOTHERLODE_DEPLETED_LEFT: // Depleted motherlode vein
				case ObjectID.MOTHERLODE_DEPLETED_MIDDLE: // Depleted motherlode vein
				case ObjectID.MOTHERLODE_DEPLETED_RIGHT: // Depleted motherlode vein
				{
					addRockRespawn(Rock.MLM_ORE_VEIN, WorldPoint.fromCoord(locCoord), ticks);
					break;
				}
				case ObjectID.DWARF_GOLD_DEPLETED: // Gold vein
				case ObjectID.VARLAMORE_MINING_ROCK_EMPTY: // Calcified rock
				case ObjectID.VARLAMORE_MINING_ROCK_EMPTY02: // Calcified rock
				case ObjectID.VARLAMORE_MINING_ROCK_EMPTY03: // Calcified rock
				case ObjectID.VARLAMORE_MINING_ROCK_EMPTY04: // Calcified rock
				{
					addRockRespawn(Rock.ORE_VEIN, WorldPoint.fromCoord(locCoord), ticks);
					break;
				}
				case ObjectID.ROCKS1:
				case ObjectID.ROCKS2:
				case ObjectID.ROCKS3:
				case ObjectID.MY2ARM_SALTROCK_EMPTY: // Basalt etc
				case ObjectID.PRIF_MINE_ROCKS1_EMPTY: // Trahaearn mine
				case ObjectID.FOSSIL_ASHPILE_EMPTY:
				{
					addRockRespawn(Rock.ROCK, WorldPoint.fromCoord(locCoord), ticks);
					break;
				}
				// Amethyst
				case ObjectID.AMETHYSTROCK_EMPTY:
				{
					addRockRespawn(Rock.AMETHYST, WorldPoint.fromCoord(locCoord), ticks);
					break;
				}
				// Barronite
				case ObjectID.CAMDOZAALROCK1_EMPTY:
				case ObjectID.CAMDOZAALROCK2_EMPTY:
				{
					addRockRespawn(Rock.BARRONITE, WorldPoint.fromCoord(locCoord), ticks);
					break;
				}
			}
		}
	}

	private void addRockRespawn(Rock rock, WorldPoint point, int ticks)
	{
		RockRespawn rockRespawn = new RockRespawn(rock, point, Instant.now(), ticks * Constants.GAME_TICK_LENGTH, rock.getZOffset());
		respawns.add(rockRespawn);
		log.debug("Adding respawn for rock: {} coord: {} ticks: {}", rock, point, ticks);
	}
}