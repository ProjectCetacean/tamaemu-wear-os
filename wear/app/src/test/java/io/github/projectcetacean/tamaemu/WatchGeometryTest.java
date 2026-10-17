package io.github.projectcetacean.tamaemu;
/** Checks full clickable rectangles, not just circular button artwork. */
public final class WatchGeometryTest {
 public static void main(String[] args){
  shellTargetStaysOnSmallLcd();
  shellTargetAdjacentWhenCenterBlocked();
  overlayMissingReservedSizing();
  shallowLayoutBoundaries();
  UiPreferences large=UiPreferences.defaults().withSize(UiPreferences.Size.LARGE).with("controlSpace","OVERLAY");
  same(WatchGeometry.calculate(480,480,2,true,large,true).display,WatchGeometry.calculate(480,480,2,true,large,false).display,"viewportInvariantAcrossVisibility LARGE");
  reportedExample();
  integerSmallUsesShellTapTarget();
  int cases=0;
  for(int dp:new int[]{192,200,227,240,300})for(float density:new float[]{1,1.5f,2,3})for(boolean round:new boolean[]{true,false})for(int inset:new int[]{-7,0,7}){
   int w=Math.round(dp*density),h=w+Math.round(inset*density);
   for(UiPreferences.ControlSpace space:UiPreferences.ControlSpace.values())for(UiPreferences.Placement placement:UiPreferences.Placement.values()){
    int previous=0;
    for(UiPreferences.Size size:UiPreferences.Size.values())for(boolean integer:new boolean[]{false,true}){
     UiPreferences p=UiPreferences.defaults().withSize(size).with("controlSpace",space.name()).with("placement",placement.name()).with("integerPixels",Boolean.toString(integer));
     WatchGeometry g=WatchGeometry.calculate(w,h,density,round,p,true),hidden=WatchGeometry.calculate(w,h,density,round,p,false);
     check(g.valid && hidden.valid,"supported viewport failed "+w+"x"+h+" "+space+" "+placement+" "+size);
     same(g.display,hidden.display,"viewportInvariantAcrossVisibility");
     same(g.displayTapTarget,hidden.displayTapTarget,"visibility preserves presentation tap target");
     for(int i=0;i<3;i++)same(g.button[i],hidden.button[i],"visibility preserves button geometry");
     contained(g.display,w,h,density,round);contained(g.settingsPanel,w,h,density,round);
     check(g.display.width()==g.display.height()&&g.image>0,"complete square");
     if(!integer){check(g.image>=previous,"sizesMonotonic");previous=g.image;}
     if(space==UiPreferences.ControlSpace.OVERLAY && size==UiPreferences.Size.LARGE && !integer)check(g.image==g.settingsPanel.width(),"overlayLargeIsSafeSquare");
     if(integer && g.availableSide>=128){
      check(g.integerFactor>0 && !g.integerFallback && g.image==g.integerFactor*128 && g.image<=g.availableSide && g.availableSide<(g.integerFactor+1)*128,"integerReported whole multiple");
     }else{
      check(g.integerFactor==0 && g.integerFallback==(integer && g.availableSide<128) && g.image==g.availableSide,"integerReported fit/fallback");
     }
     check(g.placementUsed==(g.placementFallback?UiPreferences.Placement.BOTTOM:placement),"placementReported");
     if(placement==UiPreferences.Placement.BOTTOM)check(!g.placementFallback,"bottomAlwaysFits");
     targetsValidPerPlacement(g,p,w,h,density,round);
     cases+=2;
    }
   }
  }
  check(!WatchGeometry.calculate(100,100,1,true,UiPreferences.defaults(),true).valid,"tiny viewport reports failure");
  System.out.println("WatchGeometryTest: "+cases+" full rectangle/device/preset/inset cases passed");
 }
 static void overlayMissingReservedSizing(){
  AssertionError failures=new AssertionError("overlayMissingReservedSizing must report invalid geometry instead of throwing");
  for(UiPreferences.Size size:new UiPreferences.Size[]{UiPreferences.Size.BALANCED,UiPreferences.Size.SMALL}){
   UiPreferences p=UiPreferences.defaults().withSize(size).with("controlSpace","OVERLAY");
   try{
    invalid(WatchGeometry.calculate(300,56,1f,false,p,true),false,"300x56 OVERLAY "+size);
   }catch(RuntimeException ex){
    failures.addSuppressed(new AssertionError("300x56 OVERLAY "+size+" threw instead of reporting invalid",ex));
   }
  }
  if(failures.getSuppressed().length>0)throw failures;
 }
 static void shallowLayoutBoundaries(){
  for(int h:new int[]{55,56,64})for(boolean round:new boolean[]{false,true})
   for(UiPreferences.ControlSpace space:UiPreferences.ControlSpace.values())for(UiPreferences.Placement placement:UiPreferences.Placement.values())
    for(UiPreferences.Size size:new UiPreferences.Size[]{UiPreferences.Size.BALANCED,UiPreferences.Size.SMALL})
     for(boolean integer:new boolean[]{false,true})for(boolean visible:new boolean[]{false,true}){
      UiPreferences p=UiPreferences.defaults().withSize(size).with("controlSpace",space.name()).with("placement",placement.name()).with("integerPixels",Boolean.toString(integer));
      invalid(WatchGeometry.calculate(300,h,1f,round,p,visible),integer,"shallow layout "+h+" "+round+" "+space+" "+placement+" "+size);
     }
  for(UiPreferences.Placement placement:UiPreferences.Placement.values())for(boolean integer:new boolean[]{false,true})for(boolean visible:new boolean[]{false,true}){
   UiPreferences p=UiPreferences.defaults().with("controlSpace","OVERLAY").with("placement",placement.name()).with("integerPixels",Boolean.toString(integer));
   // Large does not need a reserved sizing reference; there is shell space beside ABC.
   for(int h:new int[]{56,64}){
    WatchGeometry g=WatchGeometry.calculate(300,h,1f,false,p.withSize(UiPreferences.Size.LARGE),visible);
    check(g.valid && g.availableSide==h-8 && g.image==h-8 && g.integerFactor==0 && g.integerFallback==integer,"shallow Large keeps independent safe-square sizing");
    shallowOverlayTargets(g,h);
   }
   invalid(WatchGeometry.calculate(300,55,1f,false,p.withSize(UiPreferences.Size.LARGE),visible),integer,"shallow Large cannot fit ABC");
   // At 80px high a reserved Bottom reference exists: 16px LCD + 8px gap + 48px row + margins.
   for(UiPreferences.Size size:new UiPreferences.Size[]{UiPreferences.Size.BALANCED,UiPreferences.Size.SMALL}){
    WatchGeometry g=WatchGeometry.calculate(300,80,1f,false,p.withSize(size),visible);
    int side=size==UiPreferences.Size.BALANCED?16:13;
    check(g.valid && g.availableSide==side && g.image==side && g.integerFactor==0 && g.integerFallback==integer,"nearby overlay preserves selected size and scaling");
    shallowOverlayTargets(g,80);
   }
  }
  System.out.println("WatchGeometryTest: exact 300x56 BALANCED/SMALL crash regressions and shallow layout boundaries passed");
 }
 static void shallowOverlayTargets(WatchGeometry g,int h){
  contained(g.display,300,h,1f,false);contained(g.displayTapTarget,300,h,1f,false);
  check(g.displayTapTarget.width()==48 && g.displayTapTarget.height()==48,"shallow overlay keeps 48dp presentation target in safe shell");
  for(WatchGeometry.Bounds b:g.button){
   contained(b,300,h,1f,false);
   check(b.width()==48 && b.height()==48 && !overlap(b,g.displayTapTarget),"shallow overlay keeps safe ABC disjoint from presentation target");
  }
 }
 static void invalid(WatchGeometry g,boolean integer,String message){
  check(!g.valid,message+" reports invalid");
  check(g.availableSide==0 && g.image==0 && g.display.width()==0 && g.display.height()==0,message+" has no LCD sizing reference");
  check(g.integerFactor==0 && g.integerFallback==integer,message+" preserves requested scaling metadata");
  check(g.displayTapTarget.width()==0 && g.displayTapTarget.height()==0,message+" has no presentation target");
  for(WatchGeometry.Bounds b:g.button)check(b.width()==0 && b.height()==0,message+" has no usable controls");
 }
 static void reportedExample(){
  UiPreferences p=UiPreferences.defaults().with("controlSpace","OVERLAY").withSize(UiPreferences.Size.LARGE);
  WatchGeometry g=WatchGeometry.calculate(480,480,2,true,p,true);check(g.image==328,"reportedExample Overlay Large Fit");
  g=WatchGeometry.calculate(480,480,2,true,p.with("integerPixels","true"),true);check(g.image==256 && g.integerFactor==2 && g.availableSide==328,"reportedExample Overlay Large Integer");
  p=p.with("controlSpace","RESERVED").withSize(UiPreferences.Size.BALANCED);
  g=WatchGeometry.calculate(480,480,2,true,p,true);check(g.image==256,"reportedExample Reserved Balanced must remain 256, got "+g.image);
  p=p.withSize(UiPreferences.Size.SMALL);g=WatchGeometry.calculate(480,480,2,true,p,true);check(g.image==217,"reportedExample Reserved Small must remain 217, got "+g.image);
  g=WatchGeometry.calculate(480,480,2,true,p.with("integerPixels","true"),true);check(g.image==128 && g.integerFactor==1 && g.availableSide==217,"reportedExample Reserved Small Integer");
 }
 static void integerSmallUsesShellTapTarget(){
  UiPreferences p=UiPreferences.defaults().withSize(UiPreferences.Size.SMALL).with("controlSpace","OVERLAY").with("integerPixels","true");
  WatchGeometry g=WatchGeometry.calculate(576,555,3,true,p,true);
  check(g.valid && g.availableSide==191 && g.image==128 && g.integerFactor==1 && !g.integerFallback,"integerSmall preserves exact LCD and honest scaling");
  check(g.displayTapTarget.width()>=144 && g.displayTapTarget.height()>=144 && !within(g.displayTapTarget,g.display),"integerSmall shell target solves 128px LCD versus 144px interaction conflict");
 }
 static void shellTargetStaysOnSmallLcd(){
  AssertionError failures=new AssertionError("shellTargetStaysOnSmallLcd: detached shell target where a centered containing target is feasible");
  int cases=0;
  for(UiPreferences.ControlSpace space:UiPreferences.ControlSpace.values())for(UiPreferences.Placement placement:UiPreferences.Placement.values())for(boolean visible:new boolean[]{true,false}){
   UiPreferences p=UiPreferences.defaults().withSize(UiPreferences.Size.SMALL).with("integerPixels","true").with("controlSpace",space.name()).with("placement",placement.name());
   WatchGeometry g=WatchGeometry.calculate(576,555,3,false,p,visible);
   String fixture="576x555 rectangular density=3 SMALL Integer "+space+" "+placement+" visible="+visible;
   System.out.println(fixture+" lcd="+bounds(g.display)+" tap="+bounds(g.displayTapTarget));
   try{
    check(g.valid && g.image==128 && g.integerFactor==1 && !g.integerFallback,fixture+" preserves exact integer LCD");
    check(overlap(g.displayTapTarget,g.display),fixture+" target detached from LCD");
    // These eight layouts have room for a safe, button-free 144px square centered on the 128px LCD.
    check(within(g.display,g.displayTapTarget),fixture+" target should contain small LCD");
    check(g.displayTapTarget.width()==144 && g.displayTapTarget.height()==144,fixture+" keeps 48dp target");
    check(Math.abs(g.displayTapTarget.left+g.displayTapTarget.right-g.display.left-g.display.right)<=1
      && Math.abs(g.displayTapTarget.top+g.displayTapTarget.bottom-g.display.top-g.display.bottom)<=1,fixture+" target should be centered on LCD");
    targetsValidPerPlacement(g,p,576,555,3,false);
   }catch(AssertionError ex){failures.addSuppressed(ex);}
   cases++;
  }
  if(failures.getSuppressed().length>0)throw failures;
  System.out.println("WatchGeometryTest: "+cases+" exact detached-shell regressions passed");
 }
 static String bounds(WatchGeometry.Bounds b){return "["+b.left+","+b.top+","+b.right+","+b.bottom+"]";}
 static void shellTargetAdjacentWhenCenterBlocked(){
  // LCD [131,39,170,78] has its bottom 14px covered by B [126,64,174,112].
  // A 48px target ending at y=64 can cover the entire remaining 39x25px LCD strip.
  UiPreferences p=UiPreferences.defaults().withSize(UiPreferences.Size.SMALL).with("controlSpace","OVERLAY");
  for(boolean visible:new boolean[]{true,false}){
   WatchGeometry g=WatchGeometry.calculate(300,116,1,false,p,visible);
   check(g.valid && g.image==39,"adjacent shell fixture keeps selected LCD size");
   shallowOverlayTargets(g,116);
   WatchGeometry.Bounds t=g.displayTapTarget,l=g.display;
   int area=Math.max(0,Math.min(t.right,l.right)-Math.max(t.left,l.left))*Math.max(0,Math.min(t.bottom,l.bottom)-Math.max(t.top,l.top));
   check(area==975 && t.bottom==64,"blocked centered target must stay adjacent and cover available LCD strip, got "+bounds(t)+" area="+area);
  }
  System.out.println("WatchGeometryTest: blocked center uses adjacent shell target with maximum LCD coverage passed");
 }
 static void targetsValidPerPlacement(WatchGeometry g,UiPreferences p,int w,int h,float d,boolean round){
  check(g.targetPx==(int)Math.ceil(48*d),"targetPx rounds up");
  boolean horizontal=g.placementUsed==UiPreferences.Placement.BOTTOM || g.placementUsed==UiPreferences.Placement.TOP;
  int gap=(int)Math.ceil(8*d);
  WatchGeometry.Bounds expanded=new WatchGeometry.Bounds(g.display.left-gap,g.display.top-gap,g.display.right+gap,g.display.bottom+gap);
  for(int i=0;i<3;i++){
   WatchGeometry.Bounds b=g.button[i];contained(b,w,h,d,round);
   check(b.width()>=g.targetPx && b.height()>=g.targetPx,"targetsValidPerPlacement 48dp minimum");
   if(i>0)check(horizontal?g.button[i-1].right<=b.left:g.button[i-1].bottom<=b.top,"targetsValidPerPlacement ABC order");
   for(int j=0;j<i;j++)check(!overlap(b,g.button[j]),"targetsValidPerPlacement overlap");
   if(p.controlSpace==UiPreferences.ControlSpace.RESERVED)check(!overlap(b,expanded),"targetsValidPerPlacement reserved gap");
  }
  WatchGeometry.Bounds tap=g.displayTapTarget;
  check(tap.width()>=g.targetPx && tap.height()>=g.targetPx,"displayTapTarget 48dp minimum: " + w + "x" + h + " " + p.size + " " + p.placement);
  contained(tap,w,h,d,round);
  for(WatchGeometry.Bounds b:g.button)check(!overlap(tap,b),"displayTapTarget disjoint from ABC");
  check(overlap(tap,g.display),"supported displayTapTarget must intersect LCD: "+w+"x"+h+" "+p.controlSpace+" "+p.placement+" "+p.size+" integer="+p.integerPixels+" lcd="+bounds(g.display)+" tap="+bounds(tap));
  // Every supported matrix LCD large enough for a target must retain an uncovered one.
  if(g.image>=g.targetPx)check(within(tap,g.display),"displayTapTarget prefers uncovered LCD square");
 }
 static void same(WatchGeometry.Bounds a,WatchGeometry.Bounds b,String message){check(a.left==b.left && a.top==b.top && a.right==b.right && a.bottom==b.bottom,message);}
 static boolean within(WatchGeometry.Bounds a,WatchGeometry.Bounds b){return a.left>=b.left&&a.top>=b.top&&a.right<=b.right&&a.bottom<=b.bottom;}
 static boolean overlap(WatchGeometry.Bounds a,WatchGeometry.Bounds b){return a.left<b.right&&a.right>b.left&&a.top<b.bottom&&a.bottom>b.top;}
 static void contained(WatchGeometry.Bounds b,int w,int h,float d,boolean round){
  double margin=Math.ceil(4*d),r=Math.min(w,h)/2.0-margin;
  for(int x:new int[]{b.left,b.right})for(int y:new int[]{b.top,b.bottom}){
   check(round?Math.hypot(x-w/2.0,y-h/2.0)<=r+0.00001:x>=margin&&y>=margin&&x<=w-margin&&y<=h-margin,"clipped rectangle "+w+"x"+h+" "+x+","+y);
  }
 }
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
}
