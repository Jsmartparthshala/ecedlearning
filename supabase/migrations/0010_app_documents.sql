-- Documents the app displays but does not compile: privacy policy, terms,
-- data handling, open-source notices.
--
-- These have to be editable without shipping an APK. A privacy policy that can
-- only change when forty sideloaded televisions each accept an update is a
-- policy that is wrong for however long that takes, and the one document class
-- where being out of date is a legal problem rather than a cosmetic one.
--
-- Deliberately documents, not strings. A general remote-string table sounds like
-- the same idea and is not: it makes every label in the product untestable,
-- unversioned and untranslatable, and the failure mode is a television showing
-- an empty button. A document is a whole unit with a title, a body and a
-- version, it renders on a screen of its own, and the app ships a bundled copy
-- to fall back to - so the worst case is a school reading last month's wording
-- rather than reading nothing.
--
-- Seeded with placeholders on purpose, and they say so in their own body text.
-- Nobody should be able to open this screen on a demo television and mistake
-- filler for a policy Jagdamba has actually adopted.

create table if not exists app_documents (
  slug          text primary key,
  kind          text not null default 'legal'
                  check (kind in ('legal', 'help', 'notice')),

  title_en      text not null,
  title_np      text,

  -- Plain text with blank-line paragraphs and ALL-CAPS headings. Not HTML and
  -- not markdown: this renders into a TextView on a box with a Mali-450, and a
  -- renderer is a dependency, an attack surface and a thing to get wrong on a
  -- screen nobody is watching.
  body_en       text not null,
  body_np       text,

  -- What the school agreed to, so a change is visible as a change. Free text
  -- rather than a number because legal versions are dated, not incremented.
  version       text not null default 'draft',
  effective_on  date,

  -- Unpublished rows are drafts. The television never sees them, so a policy can
  -- be written across several sittings in the console without a half-finished
  -- paragraph appearing in a classroom.
  published     boolean not null default false,

  sort_order    int not null default 0,
  updated_at    timestamptz not null default now()
);

create index if not exists app_documents_kind_idx
  on app_documents (kind, sort_order);

alter table app_documents enable row level security;

-- Readable by the televisions, and only what is published. Writes have no
-- policy at all, which under RLS means nobody but the service role can make
-- one - the same shape as every other operator-owned table here: the ops
-- console holds that key server-side and the anon key in the APK cannot write.
drop policy if exists app_documents_read on app_documents;
create policy app_documents_read on app_documents
  for select to anon, authenticated using (published = true);

-- ------------------------------------------------------------ placeholders
--
-- Bodies are written as placeholders that identify themselves. The headings are
-- real - they are the sections these documents have to contain for a product
-- that runs in schools and touches children's data - so replacing the filler is
-- a matter of writing under each one rather than starting from a blank page.

insert into app_documents (slug, kind, title_en, title_np, version, sort_order, published, body_en)
values
  ('privacy', 'legal', 'Privacy Policy', 'गोपनीयता नीति', 'placeholder', 10, true,
$doc$PLACEHOLDER — NOT YET A POLICY

This text is a placeholder shipped so the screen exists and can be found. It
has not been reviewed by anyone and Jagdamba Smart Pathshala has not adopted
it. Replace it in the ops console before this television is used in a school.

WHAT THIS APPLICATION COLLECTS

Describe here what the television records. As built, that is: a hardware
identifier, the school and class it has been assigned to, which lessons have
been watched and how far, and the application version. Say plainly that no
student names, photographs, audio or video are collected by the television.

WHO CAN SEE IT

Describe who has access through the operations console and on what basis.

HOW LONG IT IS KEPT

State the retention period for watch history and for device records.

CHILDREN

State the position on data belonging to children, and name the guardian or
school authority who consents on their behalf.

CONTACT

Name a person and an address a school can write to.$doc$),

  ('terms', 'legal', 'Terms of Use', 'प्रयोगका सर्तहरू', 'placeholder', 20, true,
$doc$PLACEHOLDER — NOT YET AN AGREEMENT

This text is a placeholder shipped so the screen exists and can be found. It
has not been reviewed by anyone and Jagdamba Smart Pathshala has not adopted
it. Replace it in the ops console before this television is used in a school.

WHO THIS AGREEMENT IS WITH

Name the parties: the school, and the operator of this service.

WHAT IS PROVIDED

Describe the service: a television application delivering curriculum video, a
catalogue maintained centrally, and updates delivered over the network.

WHAT THE SCHOOL AGREES TO

Set out the school's obligations — supervision of use, care of the hardware,
and not redistributing the video.

AVAILABILITY

State what is and is not promised about uptime, and that lessons require a
working internet connection.

CONTENT

State where the curriculum content comes from and who holds rights in it.

ENDING THIS AGREEMENT

Describe how either side ends it and what happens to the device and the data.$doc$),

  ('data', 'legal', 'Student Data and Safeguarding', 'विद्यार्थी डेटा', 'placeholder', 30, true,
$doc$PLACEHOLDER — NOT YET A POLICY

This text is a placeholder shipped so the screen exists and can be found.
Replace it in the ops console before this television is used in a school.

WHY THIS IS SEPARATE

This product runs in classrooms of young children. The privacy policy covers
what is collected; this document is for the school and covers who is
accountable for it.

WHAT IS NEVER COLLECTED

State clearly what the television does not do. As built it has no camera, no
microphone access, and no student login — there is nothing on this device that
identifies an individual child.

WHAT IS ASSIGNED, NOT ENTERED

Explain reverse provisioning: the television is assigned to a school, a class
and a teacher from the central office. Nobody signs in on the television and no
password is ever typed into it with a remote control.

WHO TO RAISE A CONCERN WITH

Name a person and how to reach them.$doc$),

  ('licences', 'legal', 'Open Source Notices', 'खुला स्रोत सूचना', 'placeholder', 40, true,
$doc$PLACEHOLDER — INCOMPLETE

This application is built on open source software whose licences require that
their notices be reproduced. This list is not yet complete and must be
finished before distribution.

Generate the real list from the build rather than writing it by hand.

AndroidX (androidx.core, appcompat, leanback, recyclerview, lifecycle, work)
    Apache License 2.0

AndroidX Media3 / ExoPlayer
    Apache License 2.0

Kotlin standard library and kotlinx.coroutines
    Apache License 2.0

Ktor
    Apache License 2.0

supabase-kt
    MIT License

Full licence texts must accompany these notices.$doc$)
on conflict (slug) do nothing;

notify pgrst, 'reload schema';
