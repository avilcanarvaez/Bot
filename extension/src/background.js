/*
 * DragonShot — service worker mínimo.
 *
 * El icono de la extensión no abre un popup del navegador (eso hacía que el
 * primer clic se perdiera al devolver el foco a la página). En su lugar,
 * muestra u oculta la ventanita persistente que ya vive en la página.
 */
'use strict';

chrome.action.onClicked.addListener(function (tab) {
  if (!tab || tab.id == null) return;
  chrome.tabs.sendMessage(tab.id, { type: 'dragonshot-toggle-panel' }, function () {
    if (chrome.runtime.lastError) {
      // La pestaña puede estar abierta antes de instalar/recargar la extensión.
      chrome.action.setBadgeText({ tabId: tab.id, text: '!' });
      chrome.action.setBadgeBackgroundColor({ tabId: tab.id, color: '#fbbf24' });
      setTimeout(function () {
        chrome.action.setBadgeText({ tabId: tab.id, text: '' });
      }, 1800);
    }
  });
});
