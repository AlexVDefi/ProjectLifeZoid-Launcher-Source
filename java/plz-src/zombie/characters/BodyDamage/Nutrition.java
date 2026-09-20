package zombie.characters.BodyDamage;

import java.nio.ByteBuffer;
import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.UsedFromLua;
import zombie.Lua.LuaEventManager;
import zombie.ai.states.ClimbOverFenceState;
import zombie.ai.states.ClimbThroughWindowState;
import zombie.ai.states.SwipeStatePlayer;
import zombie.characters.IsoPlayer;
import zombie.characters.skills.PerkFactory.Perks;
import zombie.network.GameClient;
import zombie.scripting.objects.CharacterTrait;

@UsedFromLua
public final class Nutrition {
   private final IsoPlayer parent;
   private float carbohydrates;
   private float lipids;
   private float proteins;
   private float calories;
   private final float carbohydratesDecreraseFemale = 0.0035F;
   private final float carbohydratesDecreraseMale = 0.0035F;
   private final float lipidsDecreraseFemale = 0.00113F;
   private final float lipidsDecreraseMale = 0.00113F;
   private final float proteinsDecreraseFemale = 8.6E-4F;
   private final float proteinsDecreraseMale = 8.6E-4F;
   private final float caloriesDecreraseFemaleNormal = 0.016F;
   private final float caloriesDecreaseMaleNormal = 0.016F;
   private final float caloriesDecreraseFemaleExercise = 0.13F;
   private final float caloriesDecreaseMaleExercise = 0.13F;
   private final float caloriesDecreraseFemaleSleeping = 0.003F;
   private final float caloriesDecreaseMaleSleeping = 0.003F;
   private final int caloriesToGainWeightMale = 1000;
   private final int caloriesToGainWeightMaxMale = 4000;
   private final int caloriesToGainWeightFemale = 1000;
   private final int caloriesToGainWeightMaxFemale = 4000;
   private final int caloriesDecreaseMax = 2500;
   private final float weightGain = 1.3E-5F;
   private final float weightLoss = 8.5E-6F;
   private double weight = 60.0;
   private int updatedWeight;
   private final boolean isFemale = false;
   private float caloriesMax;
   private float caloriesMin;
   private boolean incWeight;
   private boolean incWeightLot;
   private boolean decWeight;

   public Nutrition(IsoPlayer parent) {
      this.parent = parent;
      this.setWeight(80.0);
      this.setCalories(800.0F);
   }

   /** PLZ: the sandbox flag now gates ONLY the engine's own macro and calorie simulation. Weight
    *  runs either way, because PLZ_Diet feeds it and the flag is off on this server. */
   public void update() {
      if (this.parent != null && !this.parent.isDead()) {
         if (!this.parent.isGodMod()) {
            if (SandboxOptions.instance.nutrition.getValue() && !GameClient.client) {
               this.setCarbohydrates(this.getCarbohydrates() - 0.0035F * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
               this.setLipids(this.getLipids() - 0.00113F * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
               this.setProteins(this.getProteins() - 8.6E-4F * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
               this.updateCalories();
            }

            this.updateWeight();
         }
      }
   }

   private void updateCalories() {
      float modifier = 1.0F;
      if (!this.parent.getCharacterActions().isEmpty()) {
         modifier = this.parent.getCharacterActions().get(0).caloriesModifier;
      }

      if (this.parent.isCurrentState(SwipeStatePlayer.instance())
         || this.parent.isCurrentState(ClimbOverFenceState.instance())
         || this.parent.isCurrentState(ClimbThroughWindowState.instance())) {
         modifier = 8.0F;
      }

      float coldMulti = 1.0F;
      if (this.parent.getBodyDamage() != null && this.parent.getBodyDamage().getThermoregulator() != null) {
         coldMulti = (float)this.parent.getBodyDamage().getThermoregulator().getEnergyMultiplier();
      }

      float caloriesDelta = (float)(this.getWeight() / 80.0);
      if (this.parent.IsRunning() && this.parent.isPlayerMoving()) {
         modifier = 1.0F;
         this.setCalories(this.getCalories() - 0.13F * modifier * caloriesDelta * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
      } else if (this.parent.isSprinting() && this.parent.isPlayerMoving()) {
         modifier = 1.3F;
         this.setCalories(this.getCalories() - 0.13F * modifier * caloriesDelta * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
      } else if (this.parent.isPlayerMoving()) {
         modifier = 0.6F;
         this.setCalories(this.getCalories() - 0.13F * modifier * caloriesDelta * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
      } else if (this.parent.isAsleep()) {
         this.setCalories(this.getCalories() - 0.003F * modifier * coldMulti * caloriesDelta * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
      } else {
         this.setCalories(this.getCalories() - 0.016F * modifier * coldMulti * caloriesDelta * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate());
      }

      if (this.getCalories() > this.caloriesMax) {
         this.caloriesMax = this.getCalories();
      }

      if (this.getCalories() < this.caloriesMin) {
         this.caloriesMin = this.getCalories();
      }
   }

   /** PLZ: vanilla re-checks the traits every 2000 updates, one per frame. A dedicated server runs
    *  at about 10fps, so that is over THREE MINUTES - far too slow for stripping a legacy trait off
    *  somebody who just logged in. The pass is five map removes, so running it ten times as often
    *  costs nothing. */
   public static final int PLZ_TRAIT_INTERVAL = 200;

   /** How fast weight drifts toward its target, per game-world second. Sized so the full 10kg band
    *  takes roughly two in-game days to cross, which is slow enough to read as a trend rather than
    *  a slider and fast enough that a week of eating well is visible. */
   public static final double PLZ_WEIGHT_RATE = 5.8E-5;

   /** getCalories() is deliberately NOT an input: with Nutrition off nothing decays the reserve
    *  while Eat() keeps adding, so reading it parks every character at the band ceiling. */
   public double getPlzWeightTarget() {
      float condition = zombie.plz.PLZDietScore.conditionOf(this.parent);
      return PLZ_MIN_WEIGHT + (PLZ_MAX_WEIGHT - PLZ_MIN_WEIGHT) * condition;
   }

   private void updateWeight() {
      this.setIncWeight(false);
      this.setIncWeightLot(false);
      this.setDecWeight(false);
      if (!GameClient.client) {
         double target = this.getPlzWeightTarget();
         double current = this.getWeight();
         double step = PLZ_WEIGHT_RATE * GameTime.getInstance().getGameWorldSecondsSinceLastUpdate();
         if (current < target) {
            this.setWeight(Math.min(target, current + step));
            this.setIncWeight(true);
         } else if (current > target) {
            this.setWeight(Math.max(target, current - step));
            this.setDecWeight(true);
         }

         this.updatedWeight++;
         if (this.updatedWeight >= PLZ_TRAIT_INTERVAL) {
            this.applyTraitFromWeight();
            this.updatedWeight = 0;
         }
      }
   }

   public void save(ByteBuffer output) {
      output.putFloat(this.getCalories());
      output.putFloat(this.getProteins());
      output.putFloat(this.getLipids());
      output.putFloat(this.getCarbohydrates());
      output.putFloat((float)this.getWeight());
   }

   public void load(ByteBuffer input) {
      this.setCalories(input.getFloat());
      this.setProteins(input.getFloat());
      this.setLipids(input.getFloat());
      this.setCarbohydrates(input.getFloat());
      this.setWeight(input.getFloat());
   }

   public void applyWeightFromTraits() {
      if (this.parent.hasTrait(CharacterTrait.EMACIATED)) {
         this.setWeight(50.0);
      }

      if (this.parent.hasTrait(CharacterTrait.VERY_UNDERWEIGHT)) {
         this.setWeight(60.0);
      }

      if (this.parent.hasTrait(CharacterTrait.UNDERWEIGHT)) {
         this.setWeight(70.0);
      }

      if (this.parent.hasTrait(CharacterTrait.OVERWEIGHT)) {
         this.setWeight(95.0);
      }

      if (this.parent.hasTrait(CharacterTrait.OBESE)) {
         this.setWeight(105.0);
      }
   }

   /** PLZ: the removals are kept and every ADD is gone. Weight still moves, so vanilla would hand
    *  out Underweight the moment somebody dipped below 75 - and those traits are not cosmetic
    *  (Underweight is a 20% melee damage cut). A character's build stays what they chose at
    *  creation; what they eat moves the number and nothing else. This also still strips a trait
    *  off a character made before the band existed. */
   public void applyTraitFromWeight() {
      this.parent.getCharacterTraits().remove(CharacterTrait.UNDERWEIGHT);
      this.parent.getCharacterTraits().remove(CharacterTrait.VERY_UNDERWEIGHT);
      this.parent.getCharacterTraits().remove(CharacterTrait.EMACIATED);
      this.parent.getCharacterTraits().remove(CharacterTrait.OVERWEIGHT);
      this.parent.getCharacterTraits().remove(CharacterTrait.OBESE);
   }

   public boolean characterHaveWeightTrouble() {
      return this.parent.hasTrait(CharacterTrait.EMACIATED)
         || this.parent.hasTrait(CharacterTrait.OBESE)
         || this.parent.hasTrait(CharacterTrait.VERY_UNDERWEIGHT)
         || this.parent.hasTrait(CharacterTrait.VERY_UNDERWEIGHT)
         || this.parent.hasTrait(CharacterTrait.OVERWEIGHT);
   }

   public boolean canAddFitnessXp() {
      if (this.parent.getPerkLevel(Perks.Fitness) >= 9 && this.characterHaveWeightTrouble()) {
         return false;
      } else {
         return this.parent.getPerkLevel(Perks.Fitness) < 6
            ? true
            : !this.parent.hasTrait(CharacterTrait.EMACIATED)
               && !this.parent.hasTrait(CharacterTrait.OBESE)
               && !this.parent.hasTrait(CharacterTrait.VERY_UNDERWEIGHT);
      }
   }

   public float getCarbohydrates() {
      return this.carbohydrates;
   }

   public void setCarbohydrates(float carbohydrates) {
      if (carbohydrates < -500.0F) {
         carbohydrates = -500.0F;
      }

      if (carbohydrates > 1000.0F) {
         carbohydrates = 1000.0F;
      }

      this.carbohydrates = carbohydrates;
   }

   public float getProteins() {
      return this.proteins;
   }

   public void setProteins(float proteins) {
      if (proteins < -500.0F) {
         proteins = -500.0F;
      }

      if (proteins > 1000.0F) {
         proteins = 1000.0F;
      }

      this.proteins = proteins;
   }

   public float getCalories() {
      return this.calories;
   }

   public void setCalories(float calories) {
      if (calories < -2200.0F) {
         calories = -2200.0F;
      }

      if (calories > 3700.0F) {
         calories = 3700.0F;
      }

      this.calories = calories;
   }

   public float getLipids() {
      return this.lipids;
   }

   public void setLipids(float lipids) {
      if (lipids < -500.0F) {
         lipids = -500.0F;
      }

      if (lipids > 1000.0F) {
         lipids = 1000.0F;
      }

      this.lipids = lipids;
   }

   public double getWeight() {
      return this.weight;
   }

   /** PLZ: body weight moves, but only inside a narrow band. Vanilla's 35-to-unbounded range is
    *  what let a character waste to 45kg or balloon past 100 and pick up traits on the way; this
    *  keeps the readout alive and responsive to how somebody eats without any of that.
    *
    *  Every write goes through here - the constructor, load(), applyWeightFromTraits(),
    *  updateWeight(), the admin command and the debug slider - so clamping here is the whole
    *  containment, and a character saved at 105 or at 45 is pulled into the band on its next load.
    *
    *  NOTE 70 is INSIDE vanilla's Underweight band (> 65 and <= 75). That is deliberate and safe
    *  only because applyTraitFromWeight below no longer ADDS traits; if that ever changes, this
    *  floor has to rise to 76 or every lean character gets a melee damage penalty back. */
   public static final double PLZ_MIN_WEIGHT = 70.0;
   public static final double PLZ_MAX_WEIGHT = 80.0;

   public void setWeight(double weight) {
      if (weight < PLZ_MIN_WEIGHT) {
         weight = PLZ_MIN_WEIGHT;
      } else if (weight > PLZ_MAX_WEIGHT) {
         weight = PLZ_MAX_WEIGHT;
      }
      this.weight = weight;
   }

   public boolean isIncWeight() {
      return this.incWeight;
   }

   public void setIncWeight(boolean incWeight) {
      this.incWeight = incWeight;
   }

   public boolean isIncWeightLot() {
      return this.incWeightLot;
   }

   public void setIncWeightLot(boolean incWeightLot) {
      this.incWeightLot = incWeightLot;
   }

   public boolean isDecWeight() {
      return this.decWeight;
   }

   public void setDecWeight(boolean decWeight) {
      this.decWeight = decWeight;
   }
}
