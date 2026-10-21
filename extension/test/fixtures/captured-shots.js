/*
 * Fixtures reales, sin token de sesión.
 *
 * look0: server 5, sala 44, Armor, body=0. Cuatro disparos propios capturados
 *        con `shot-ws.jsonl` el 2026-10-20. `power` coincide con
 *        `#last_power_mark.left - 247`.
 * look1: server 5, sala 48, Armor, body=-17. Muestra de la calibración previa.
 */
(function (root) {
  'use strict';

  root.DragonShotFixtures = {
    look0: [
      {
        name: 'look0-power-195',
        mobile: '/static/images/mobiles/Armor2D.png',
        shoot: ':931,13,0,0,27,195,14162,0,[0,9.2,1807]',
        x: 931,
        y: 13,
        look: 0,
        s: [911, -13, 153, 461, 0, 402],
        time: 1175,
      },
      {
        name: 'look0-power-250',
        mobile: '/static/images/mobiles/Armor2D.png',
        shoot: ':931,13,0,0,55,250,4041,0,[0,11,2752]',
        x: 931,
        y: 13,
        look: 0,
        s: [911, -13, 125, 591, 0, 402],
        time: 2450,
      },
      {
        name: 'look0-power-240',
        mobile: '/static/images/mobiles/Armor2D.png',
        shoot: ':931,13,0,0,55,240,3289,0,[0,10,2405]',
        x: 931,
        y: 13,
        look: 0,
        s: [911, -13, 125, 567, 0, 402],
        time: 4041,
      },
      {
        name: 'look0-power-223',
        mobile: '/static/images/mobiles/Armor2D.png',
        shoot: ':931,13,0,0,20,223,6803,0,[0,10.4,2321]',
        x: 931,
        y: 13,
        look: 0,
        s: [911, -13, 160, 527, 0, 402],
        time: 6803,
      },
    ],
    look1: [
      {
        name: 'look1-power-175',
        mobile: '/static/images/mobiles/Armor2D.png',
        // `extra` se recorta a []: no participa en la calibración.
        shoot: ':402,20,-17,1,55,175,7583,0,[]',
        x: 402,
        y: 20,
        look: 1,
        s: [414, -11, 72, 397, 0, 390],
        time: 2050,
      },
    ],
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
