/*
 * Copyright 2008-2012, David Karnok 
 * The file is part of the Open Imperium Galactica project.
 * 
 * The code should be distributed under the LGPL license.
 * See http://www.gnu.org/licenses/lgpl.html for details.
 */

package hu.openig.mechanics;

import hu.openig.core.Action0;
import hu.openig.core.Difficulty;
import hu.openig.model.AIControls;
import hu.openig.model.AIFleet;
import hu.openig.model.AIInventoryItem;
import hu.openig.model.AIPlanet;
import hu.openig.model.AIWorld;
import hu.openig.model.EquipmentSlot;
import hu.openig.model.Fleet;
import hu.openig.model.FleetTask;
import hu.openig.model.ResearchMainCategory;
import hu.openig.model.ResearchSubCategory;
import hu.openig.model.ResearchType;
import hu.openig.utils.JavaUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans the creation of various ships, equipment and vehicles.
 * @author akarnokd, 2012.01.05.
 */
public class OffensePlanner extends Planner {
	/**
	 * Initializes the planner.
	 * @param world the current world
	 * @param controls the controls
	 */
	public OffensePlanner(AIWorld world, AIControls controls) {
		super(world, controls);
	}

	@Override
	protected void plan() {
		// have a fleet for every N planets + 1
		int divider = 5;
		if (w.difficulty == Difficulty.NORMAL) {
			divider = 4;
		} else
		if (w.difficulty == Difficulty.HARD) {
			divider = 3;
		}
		if (world.ownFleets.size() >= world.ownPlanets.size() / divider + 1) {
			checkFleetUpgrade();
			return;
		}
		
		if (world.money < 100000) {
			return;
		}

		// construct fleets
		
		List<ResearchType> fighters = new ArrayList<ResearchType>();
		List<ResearchType> cruisers = new ArrayList<ResearchType>();
		List<ResearchType> battleships = new ArrayList<ResearchType>();
		
		for (ResearchType rt : world.availableResearch) {
			if (rt.category == ResearchSubCategory.SPACESHIPS_BATTLESHIPS) {
				if (!rt.id.equals("ColonyShip")) {
					battleships.add(rt);
				}
			} else
			if (rt.category == ResearchSubCategory.SPACESHIPS_CRUISERS) {
				cruisers.add(rt);
			} else
			if (rt.category == ResearchSubCategory.SPACESHIPS_FIGHTERS) {
				fighters.add(rt);
			}
		}
		
		final int cruiserBatch = 2;
		final int fighterBatch = 10;
		final int battleshipBatch = 1;
		
		if (checkProduction(fighters, 30, fighterBatch)) {
			return;
		}
		if (checkProduction(cruisers, 25, cruiserBatch)) {
			return;
		}
		if (checkOrbitalFactory()) {
			return;
		}
		if (checkProduction(battleships, 3, battleshipBatch)) {
			return;
		}
		
		if (checkMilitarySpaceport()) {
			return;
		}

		// check if we met the inventory level to deploy a fleet
		final List<ResearchType> bigShips = new ArrayList<ResearchType>();
		final List<ResearchType> mediumShip = new ArrayList<ResearchType>();
		final Map<ResearchType, Integer> smallShips = new HashMap<ResearchType, Integer>();
		
		Collections.sort(battleships, expensiveFirst);
		for (ResearchType rt : battleships) {
			int count = world.inventoryCount(rt);
			while (bigShips.size() < 3 && count > 0) {
				bigShips.add(rt);
				count--;
			}
		}
		Collections.sort(cruisers, expensiveFirst);
		for (ResearchType rt : cruisers) {
			int count = world.inventoryCount(rt);
			while (mediumShip.size() < 25 && count > 0) {
				mediumShip.add(rt);
				count--;
			}
		}
		Collections.sort(fighters, expensiveFirst);
		int totalFighters = 0;
		for (ResearchType rt : fighters) {
			int invCount = world.inventoryCount(rt);
			if (invCount > 0) {
				int ij = Math.min(30, invCount);
				smallShips.put(rt, ij);
				totalFighters += ij;
			}
		}
		
		// check load levels
		if (bigShips.size() >= 3 && mediumShip.size() >= 25
				&& totalFighters >= fighters.size() * 30) {
			// count required equipment
			List<ResearchType> rts = new ArrayList<ResearchType>(mediumShip);
			rts.addAll(bigShips);
			Map<ResearchType, Integer> equipmentDemands = countEquipments(rts);
	
			List<ResearchType> equipments = new ArrayList<ResearchType>();
			List<ResearchType> weapons = new ArrayList<ResearchType>();
			for (Map.Entry<ResearchType, Integer> e : equipmentDemands.entrySet()) {
				ResearchType rt = e.getKey();
				int count = e.getValue();
				if (count > world.inventoryCount(rt)) {
					if (rt.category.main == ResearchMainCategory.EQUIPMENT) {
						equipments.add(rt);
					} else
					if (rt.category.main == ResearchMainCategory.WEAPONS) {
						weapons.add(rt);
					}
				}
			}
			
			if (checkProduction(equipmentDemands)) {
				return;
			}
	
			// check if all demand met
			for (Map.Entry<ResearchType, Integer> e : equipmentDemands.entrySet()) {
				ResearchType rt = e.getKey();
				int count = e.getValue();
				if (count > world.inventoryCount(rt)) {
					return;
				}
			}		
			
			// count vehicle capacity
			int vehicleCount = 0;
			for (ResearchType rt : bigShips) {
				if (rt.has("vehicles")) {
					vehicleCount += rt.getInt("vehicles");
				}
				for (EquipmentSlot es : rt.slots.values()) {
					ResearchType bay = null;
					if (es.fixed) {
						if (es.items.get(0).has("vehicles")) {
							bay = es.items.get(0);
						}
					} else {
						for (ResearchType rt0 : es.items) {
							if (rt0.has("vehicles") && world.isAvailable(rt0)) {
								bay = rt0;
							}
						}
					}
					if (bay != null) {
						vehicleCount += bay.getInt("vehicles");
					}
				}
			}
			
			final VehiclePlan plan = planVehicles(vehicleCount);
			if (plan == null) {
				return;
			}
			// select a spaceport
			final AIPlanet spaceport = findBestMilitarySpaceport();
			
			add(new Action0() {
				@Override
				public void invoke() {
					Fleet f = controls.actionCreateFleet(label(p.id + ".fleet"), spaceport.planet);
					boolean success = true;
					for (ResearchType rt : bigShips) {
						if (f.owner.inventoryCount(rt) > 0) {
							f.addInventory(rt, 1);
							f.owner.changeInventoryCount(rt, -1);
						} else {
							success = false;
							break;
						}
					}
					for (ResearchType rt : mediumShip) {
						if (f.owner.inventoryCount(rt) > 0) {
							f.addInventory(rt, 1);
							f.owner.changeInventoryCount(rt, -1);
						} else {
							success = false;
							break;
						}
					}
					for (Map.Entry<ResearchType, Integer> cfg : smallShips.entrySet()) {
						int cnt = cfg.getValue();
						ResearchType rt = cfg.getKey();
						if (cnt <= f.owner.inventoryCount(rt)) {
							f.addInventory(rt, cnt);
							f.owner.changeInventoryCount(rt, -cnt);
						} else {
							success = false;
							break;
						}
					}
					if (plan.bestTank != null) {
						if (plan.tankCount <= f.owner.inventoryCount(plan.bestTank)) {
							f.addInventory(plan.bestTank, plan.tankCount);
							f.owner.changeInventoryCount(plan.bestTank, -plan.tankCount);
						} else {
							success = false;
						}
					}
					for (Map.Entry<ResearchType, Integer> cfg : plan.vehicleConfig.entrySet()) {
						int cnt = cfg.getValue();
						ResearchType rt = cfg.getKey();
						if (cnt <= f.owner.inventoryCount(rt)) {
							f.addInventory(rt, cnt);
							f.owner.changeInventoryCount(rt, -cnt);
						} else {
							success = false;
							break;
						}
					}
					
					// inventory failed
					if (f.inventory.size() == 0 || !success) {
						log("DeployFleet, Failed = Inventory insufficient");
						f.owner.world.removeFleet(f);
					} else {
						f.upgradeAll();
					}
				}
			});
		}
	}
	/**
	 * Count the required equipments.
	 * @param ships the ships
	 * @return the map of technology to count
	 */
	Map<ResearchType, Integer> countEquipments(List<ResearchType> ships) {
		Map<ResearchType, Integer> result = JavaUtils.newHashMap();
		
		for (ResearchType rt : ships) {
			for (EquipmentSlot es : rt.slots.values()) {
				if (!es.fixed) {
					ResearchType req = null;
					// find best available tech
					for (ResearchType rt0 : es.items) {
						if (world.isAvailable(rt0)) {
							req = rt0;
						}
					}
					if (req != null) {
						Integer v = result.get(req);
						result.put(req, v != null ? v + es.max : es.max);
					}
				}
			}
		}
		
		return result;
	}
	/**
	 * Check if the fleet could be upgraded.
	 */
	void checkFleetUpgrade() {
		Set<AIFleet> toUpgrade = new HashSet<AIFleet>();
		// upgrade fleet
		for (AIFleet f : world.ownFleets) {
			if (f.task == FleetTask.UPGRADE && f.isMoving()) {
				return;
			}
		}
		for (final AIFleet f : world.ownFleets) {
			if (f.task == FleetTask.UPGRADE 
					&& !f.isMoving() && f.statistics.planet != null) {
				// decomission fleet
				add(new Action0() {
					@Override
					public void invoke() {
						f.fleet.strip();
						f.fleet.sell();
						log("FleetDecomission, Fleet = %s", f.fleet.name);
					}
				});
				return;
			}
		}
		for (AIFleet f : world.ownFleets) {
			if (f.task.ordinal() > FleetTask.UPGRADE.ordinal()) {
				if (f.statistics.vehicleCount > 0) {
					TankChecker tc = new TankChecker();
					if (tc.check(f.inventory)) {
						toUpgrade.add(f);
					}
				} else
				if (isBetter(f.inventory, ResearchSubCategory.SPACESHIPS_FIGHTERS)) {
					toUpgrade.add(f);
				} else
				if (isBetter(f.inventory, ResearchSubCategory.SPACESHIPS_CRUISERS)) {
					toUpgrade.add(f);
				} else
				if (isBetter(f.inventory, ResearchSubCategory.SPACESHIPS_BATTLESHIPS)) {
					toUpgrade.add(f);
				}
			}
		}
		if (!toUpgrade.isEmpty()) {
			if (checkMilitarySpaceport()) {
				return;
			}
			// find the weakest fleet and move it to the closest spaceport
			final AIFleet min = Collections.min(toUpgrade, new Comparator<AIFleet>() {
				@Override
				public int compare(AIFleet o1, AIFleet o2) {
					return o1.statistics.firepower - o2.statistics.firepower;
				}
			});
			final AIPlanet spaceport = findClosestMilitarySpaceport(min.x, min.y);
			add(new Action0() {
				@Override
				public void invoke() {
					min.fleet.task = FleetTask.UPGRADE;
					controls.actionMoveFleet(min.fleet, spaceport.planet);
				}
			});
			return;
		}
	}
	/**
	 * Check if a better technology is available. 
	 * @param inv the inventory
	 * @param cat the category filter
	 * @return true if better technology is available.
	 */
	boolean isBetter(Iterable<AIInventoryItem> inv, ResearchSubCategory cat) {
		ResearchType current = null;
		ResearchType best = null;
		for (ResearchType rt : world.availableResearch) {
			if (rt.category == cat) {
				if (best == null || best.productionCost < rt.productionCost) {
					best = rt;
				}
			}
		}
		for (AIInventoryItem ii : inv) {
			if (ii.type.category == cat) {
				if (current == null || current.productionCost < ii.type.productionCost) {
					current = ii.type;
				}
			}
		}
		return (current == null && best != null) || (current != null && best != null && current.productionCost < best.productionCost);
	}
}
