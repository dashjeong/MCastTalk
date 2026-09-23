"use strict";
// A pure per-speaker policy shared by browser playback and deterministic tests.
(function(root){
  function knows(p, language){return Boolean(language)&&(language===p.listenLanguage||language===p.secondaryOriginalLanguage);}
  function policy(p, sourceLanguage, originalRatio=.2){
    const known=knows(p,sourceLanguage), mode=p.audioMode||'both', duck=p.duckingMode||'ducked_original';
    if(mode==='captions_only')return {originalVolume:0,translated:false,known};
    if(mode==='original'||duck==='original_only'||known)return {originalVolume:1,translated:false,known};
    const originalVolume=mode==='translated'||duck==='translated_only'?0:Math.max(0,Math.min(1,Number(originalRatio)||0));
    return {originalVolume,translated:true,known};
  }
  const api={knows,policy};
  if(typeof module!=='undefined'&&module.exports)module.exports=api;
  else root.MCastTalkAudioPolicy=api;
})(typeof window!=='undefined'?window:globalThis);
