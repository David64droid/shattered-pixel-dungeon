/*
 * Shattered Pixel Dungeon - Modo 2 jugadores (hot-seat)
 *
 * Dos heroes reales comparten la misma partida y el mismo nivel. Los turnos se
 * alternan: cuando le toca a un heroe, pasa a ser Dungeon.hero (el "heroe activo")
 * y todo el codigo existente del juego sigue funcionando sin cambios. El otro heroe
 * es Coop.partner. Cada jugador tiene su propio inventario, talentos, buffs y
 * atajos rapidos. El oro, las estadisticas y el diario son compartidos.
 */
package com.shatteredpixel.shatteredpixeldungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Game;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;

import java.util.ArrayList;

public class Coop {

	//el otro heroe (el que NO esta siendo controlado ahora). null = partida de 1 jugador
	public static Hero partner = null;

	//true si Dungeon.hero es actualmente el jugador 2
	public static boolean activeIsP2 = false;

	//atajos rapidos del heroe que no esta activo
	public static QuickSlot partnerQuickslot = new QuickSlot();

	private static final String PARTNER     = "coop_partner";
	private static final String PARTNER_QS  = "coop_partner_quickslot";
	private static final String ACTIVE_P2   = "coop_active_p2";

	// ------------------------------------------------------------------ estado

	public static boolean active() {
		return partner != null;
	}

	public static void reset() {
		partner = null;
		activeIsP2 = false;
		partnerQuickslot = new QuickSlot();
	}

	/** Heroes vivos, el activo primero. */
	public static ArrayList<Hero> heroes() {
		ArrayList<Hero> list = new ArrayList<>(2);
		if (Dungeon.hero != null && Dungeon.hero.isAlive()) list.add(Dungeon.hero);
		if (partner != null && partner.isAlive()) list.add(partner);
		return list;
	}

	public static boolean isPartnerAlive() {
		return partner != null && partner.isAlive();
	}

	/** 1 o 2, segun a que jugador pertenece el heroe dado. */
	public static int playerNumber(Hero h) {
		if (h == Dungeon.hero) return activeIsP2 ? 2 : 1;
		return activeIsP2 ? 1 : 2;
	}

	// --------------------------------------------------------------- turnos

	/** Intercambia quien es Dungeon.hero. Se llama desde el hilo de actores. */
	public static void swapControl() {
		if (partner == null) return;

		Hero old = Dungeon.hero;
		Hero now = partner;

		Dungeon.hero = now;
		partner = old;
		activeIsP2 = !activeIsP2;

		QuickSlot q = Dungeon.quickslot;
		Dungeon.quickslot = partnerQuickslot;
		partnerQuickslot = q;

		old.setCoopActive(false);
		now.setCoopActive(true);

		//la vision es la del heroe activo
		refreshView(now);

		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				GameScene.heroSwapped();
			}
		});
	}

	private static void refreshView(Hero h) {
		if (Dungeon.level != null) {
			com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Light light =
					h.buff(com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Light.class);
			h.viewDistance = light == null ? Dungeon.level.viewDistance
					: Math.max(com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Light.DISTANCE, Dungeon.level.viewDistance);
		}
	}

	/**
	 * Llamado por Hero.die() cuando muere un heroe pero el otro sigue vivo.
	 * Devuelve true si se ha gestionado (la partida continua).
	 */
	public static boolean onHeroDeath(Hero dead) {
		if (partner == null) return false;

		Hero survivor = (dead == Dungeon.hero) ? partner : Dungeon.hero;
		if (survivor == null || !survivor.isAlive()) return false;

		//soltar el inventario del caido alrededor de su cuerpo
		int pos = dead.pos;
		ArrayList<Integer> cells = new ArrayList<>();
		for (int ofs : PathFinder.NEIGHBOURS8) {
			int cell = pos + ofs;
			if ((Dungeon.level.passable[cell] || Dungeon.level.avoid[cell]) && Dungeon.level.heaps.get(cell) == null) {
				cells.add(cell);
			}
		}
		ArrayList<Item> items = new ArrayList<>(dead.belongings.backpack.items);
		for (Item item : items) {
			int cell = cells.isEmpty() ? pos : cells.remove(Random.Int(cells.size()));
			Dungeon.level.drop(item, cell).sprite.drop(pos);
		}

		boolean deadWasActive = (dead == Dungeon.hero);
		int deadNumber = playerNumber(dead);

		if (deadWasActive) {
			//pasamos el control al superviviente
			Dungeon.hero = survivor;
			Dungeon.quickslot = partnerQuickslot;
			//el superviviente era el companero, asi que cambia de numero de jugador
			activeIsP2 = !activeIsP2;
		}
		partner = null; //a partir de ahora, partida de 1 jugador
		partnerQuickslot = new QuickSlot();
		//en una partida de un solo jugador no hay turnos compartidos
		survivor.setCoopActive(false);
		refreshView(survivor);

		final String msg = tr("Ha caido el jugador " + deadNumber + ". El otro sigue solo.",
				"Player " + deadNumber + " has fallen. The other carries on alone.");
		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				GLog.n(msg);
				GameScene.heroSwapped();
			}
		});
		return true;
	}

	// ------------------------------------------------------ crear al jugador 2

	/** Crea el segundo heroe con la clase indicada y lo coloca junto al primero. */
	public static void createPartner(HeroClass cls) {
		Hero h = new Hero();
		h.live();

		//initHero() reparte el equipo con item.collect(), que usa Dungeon.hero y Dungeon.quickslot,
		//asi que mientras se inicializa, el nuevo heroe tiene que ser "Dungeon.hero"
		Hero savedHero = Dungeon.hero;
		QuickSlot savedQuick = Dungeon.quickslot;
		partnerQuickslot = new QuickSlot();
		Dungeon.hero = h;
		Dungeon.quickslot = partnerQuickslot;
		try {
			cls.initHero(h);
		} finally {
			Dungeon.hero = savedHero;
			Dungeon.quickslot = savedQuick;
		}

		partner = h;
		//el jugador 1 es el activo
		activeIsP2 = false;
		savedHero.setCoopActive(true);
		h.setCoopActive(false);

		placePartnerNear(savedHero.pos);
		h.viewDistance = Dungeon.level.viewDistance;

		Actor.add(h);
		//el jugador 1 mueve primero; a partir de ahi los turnos se alternan solos
		h.spendConstant(1f);
		GameScene.addPartnerSprite(h);
	}

	/** Coloca al companero en una casilla libre cerca de pos. */
	public static void placePartnerNear(int pos) {
		if (partner == null) return;
		Level level = Dungeon.level;

		ArrayList<Integer> options = new ArrayList<>();
		for (int ofs : PathFinder.NEIGHBOURS8) {
			int c = pos + ofs;
			if (c >= 0 && c < level.length() && level.passable[c] && !level.invalidHeroPos(c)
					&& Actor.findChar(c) == null) {
				options.add(c);
			}
		}
		int dest;
		if (!options.isEmpty()) {
			dest = options.get(Random.Int(options.size()));
		} else {
			dest = level.randomRespawnCell(partner);
			if (dest < 0) dest = pos;
		}
		partner.pos = dest;
		partner.curAction = partner.lastAction = null;
		if (partner.sprite != null && partner.sprite.parent != null) partner.sprite.place(dest);
	}

	/** Cambio de nivel: el grupo viaja junto. */
	public static void onLevelSwitch(int heroPos) {
		if (partner == null) return;
		placePartnerNear(heroPos);
		refreshView(partner);
	}

	// ------------------------------------------------------------ guardado

	public static void store(Bundle bundle) {
		bundle.put(ACTIVE_P2, activeIsP2);
		if (partner == null) return;

		//los items guardan su ranura de atajo rapido a partir de Dungeon.quickslot,
		//asi que usamos temporalmente los atajos del companero al guardarlo
		QuickSlot saved = Dungeon.quickslot;
		Dungeon.quickslot = partnerQuickslot;
		try {
			bundle.put(PARTNER, partner);
		} finally {
			Dungeon.quickslot = saved;
		}
		Bundle qs = new Bundle();
		partnerQuickslot.storePlaceholders(qs);
		bundle.put(PARTNER_QS, qs);
	}

	public static void restore(Bundle bundle) {
		reset();
		if (!bundle.contains(PARTNER)) return;

		activeIsP2 = bundle.getBoolean(ACTIVE_P2);

		QuickSlot saved = Dungeon.quickslot;
		partnerQuickslot = new QuickSlot();
		Dungeon.quickslot = partnerQuickslot;
		try {
			partner = (Hero) bundle.get(PARTNER);
		} finally {
			Dungeon.quickslot = saved;
		}
		if (bundle.contains(PARTNER_QS)) {
			partnerQuickslot.restorePlaceholders(bundle.getBundle(PARTNER_QS));
		}
		if (Dungeon.hero != null) Dungeon.hero.setCoopActive(true);
		if (partner != null) partner.setCoopActive(false);
	}

	// ------------------------------------------------------------- textos

	public static String tr(String es, String en) {
		return Messages.lang() == Languages.SPANISH ? es : en;
	}

	public static void announceTurn() {
		if (Dungeon.hero == null || Dungeon.hero.sprite == null) return;
		int n = playerNumber(Dungeon.hero);
		Dungeon.hero.sprite.showStatus(n == 1 ? 0x66AAFF : 0xFF8866,
				tr("Jugador " + n, "Player " + n));
	}
}
