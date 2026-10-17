package io.github.projectcetacean.tamaemu;
import java.util.*;
public final class ButtonStateTest {
 public static void main(String[] args){
  List<String> e=new ArrayList<>();ButtonState s=new ButtonState((b,p)->e.add(b+":"+p));
  s.contact(0,10,true);s.contact(1,10,true);s.contact(0,10,false);s.contact(0,10,true);s.endContact(10);
  check(e.toString().equals("[0:true, 0:false, 0:true, 0:false]"),"pointer retains original owner and out/back edges: "+e);e.clear();
  s.contact(1,20,true);s.contact(1,21,true);s.accessibilityPulse(1,5);s.endContact(20);s.endPulse(1,4);s.endPulse(1,5);check(s.pressedMask()==2,"second physical owner survives pulse");s.endContact(21);
  check(e.toString().equals("[1:true, 1:false]"),"sources merge edges: "+e);e.clear();
  s.accessibilityPulse(2,8);s.accessibilityPulse(2,9);s.endPulse(2,8);check(s.pressedMask()==4,"old timer cannot release new pulse");s.endPulse(2,9);check(s.pressedMask()==0,"new pulse releases");e.clear();
  String[] traces={"", "[0:true, 0:false]", "[1:true, 1:false]", "[0:true, 1:true, 0:false, 1:false]", "[2:true, 2:false]", "[0:true, 2:true, 0:false, 2:false]", "[1:true, 2:true, 1:false, 2:false]", "[0:true, 1:true, 2:true, 0:false, 1:false, 2:false]"};
  for(int mask=1;mask<8;mask++){e.clear();for(int b=0;b<3;b++)if((mask&(1<<b))!=0)s.contact(b,b,true);check(s.pressedMask()==mask,"all combinations");s.releaseAll();s.releaseAll();check(s.pressedMask()==0,"release all");check(e.toString().equals(traces[mask]),"combination emits only ordered changed edges: "+e);}e.clear();
  for(String reason:new String[]{"settings","pause","session failure","layout"}){s.contact(0,7,true);s.accessibilityPulse(2,99);s.setEnabled(false);check(s.pressedMask()==0&&!s.hasActiveInput(),reason+" releases contacts/pulses");s.contact(1,9,true);s.accessibilityPulse(2,100);check(s.pressedMask()==0,"modal input blocked");s.setEnabled(true);s.endPulse(2,99);check(s.pressedMask()==0,"obsolete callback after release");}
  s.contact(0,5,false);check(s.hasActiveInput(),"contact outside still blocks display gestures");s.releaseAll();check(!s.hasActiveInput(),"cancel clears owners");
  System.out.println("ButtonStateTest: ownership, combinations, merged sources, stale pulses and modal lifecycle passed");
 }
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
}
