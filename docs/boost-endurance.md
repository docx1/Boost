# Boost-endurance: long rides and other low-step endurance exercise

This branch, `boost-endurance-4.0.0`, is the Boost 6.3.0 release for AAPS 4.0 with three changes
to how Boost handles long aerobic efforts that produce few steps, such as cycling, rowing or
paddling. It is the AAPS 4.0 port of the `Boost-endurance` line on AAPS 3.4 and behaves the same.
All three changes are live. Each can only reduce insulin. They need heart-rate integration
(Wear OS, Garmin or Health Connect) switched on, because steps alone cannot see a ride.

## Why

In the main release, Boost decides what you are doing one cycle at a time. A ride produces almost
no steps, so without heart rate the whole ride reads as inactivity and the profile is raised to
130%, which adds insulin. With heart rate, a steady effort in zone 3 or 4 reads as resistance
exercise (target 160 mg/dL, profile unchanged), but easy riding in zone 2 and every descent or stop
reads as rest and ends the exercise bout, so a ride becomes a string of short bouts.

## What changes

The inactivity raise is withheld whenever heart rate is in zone 2 or above, that is at least 30% of
your heart-rate reserve. Nothing else changes at zone 2, because ordinary daily heart rate reaches
it too. This change is also in the main release.

A new endurance state holds the whole effort together. After 30 minutes of heart rate in zone 2 or
above with fewer than 100 steps in each 15 minutes, Boost treats
you as doing endurance exercise: the profile goes to Endurance Profile Percent (60% by default) and
the target to 150 mg/dL (8.3 mmol/L), or 160 if heart rate alone has already raised it that far,
unless you have set a temporary target. A profile percentage you have set yourself is left as it
is, and so is a lower one from Activity Percent. A profile high target above 150 mg/dL is kept. The state lasts until 20 minutes pass without qualifying heart rate, so descents and café stops
do not end it. V6 treats it as exercise, which holds off the fast-carb confirm and the early primer.

A step feed that has gone quiet does not count as few steps. Without steps a ride cannot be told
from a walk or a run, so a dead feed never starts an endurance bout, and a feed that dies during a
ride ends the bout after 20 minutes. Replayed on 3.4 with steps forced to zero, a permanently dead
feed would have added up to 1.5 false bouts a week.

The recovery window after exercise grows with the length of the bout: the configured window (2 h by
default) times the bout length in hours, between 1 and 4 times, capped at 12 hours. A ride of four
hours or more therefore gets 8 hours of recovery at the defaults, with a target of 144 mg/dL
(8.0 mmol/L) and Boost's bolus cap and scale at 0.4. Bouts of an hour or less are unchanged. After
an endurance bout the window applies even when Enable Post-Exercise Recovery is off, and its target
is then never below your profile target; other exercise still follows that switch. A bout in which endurance was seen gets at least the endurance recovery
when it ends: the longer window and the stronger scale of endurance and of whatever the bout ended
as, with that type's target. A ride that ends in a walk gets the endurance window, and one that ends
in a run or a weights session gets the vigorous or resistance window at the endurance scale. A later bout that ends while a window is still open can
lengthen it but not shorten it, and the stronger of the two reductions applies.

## Settings

Both are in the Boost settings under Exercise Settings, Heart Rate Integration, below Enable HR
Integration, on which they depend. Endurance exercise (cycling, rowing) switches the state on or off
and is on by default. Endurance Profile Percent sets the reduction, from 30% to 100%. The recovery
window length, target and scale are the ones under Post-Exercise Recovery.

## Limits

Qualifying cycles during the first 30 minutes may be up to 11 minutes apart. That figure is a
modelling choice. On a five-minute loop it allows one missed or non-qualifying cycle (a short burst
of walking, a loop that did not run) plus ordinary timing jitter, and two in a row start the 30
minutes again. On a one-minute loop a dip of up to 10 minutes rides through. Once the state has
begun, gaps of up to 20 minutes do not end it.

The detector sees only heart rate and steps. Sitting at zone 2 with few steps for 30 minutes also
qualifies, as happens with an illness or a fever, or when the resting heart rate in the settings is
set too low, and it gives the 60% profile and, when it ends, a recovery window and target.

This has not been tested on a recorded ride. A weights session with steady heart rate and little
walking can qualify after 30 minutes and receive the profile reduction, where guidance for
resistance work keeps the profile. A high temporary target still switches Boost off for its
duration, as in the main release, and remains the simplest protection for a planned ride. The
length scaling of the recovery window is a modelling choice: the programme's measurements of
post-exercise hypoglycaemia come from ordinary exercise, and nothing in them covers a ride of
several hours.
