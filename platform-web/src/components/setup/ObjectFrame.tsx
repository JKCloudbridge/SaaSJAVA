"use client";

import { useParams, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { COMMON_TEXT } from "@/components/account/messages";
import { failureText } from "@/components/console/useAction";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { sectionOf } from "@/lib/shell/areas";
import { usePublishOpenObject } from "@/lib/shell/objectContext";
import { useAuthorship } from "./authorship";
import { FieldsPanel } from "./FieldsPanel";
import { LayoutsPanel } from "./LayoutsPanel";
import { ObjectDetailsPanel } from "./ObjectDetailsPanel";
import type { ObjectTabProps } from "./objectTab";
import type { FieldTypeView } from "./objectText";
import { RecordPagesPanel } from "./RecordPagesPanel";
import { RecordTypesPanel } from "./RecordTypesPanel";
import { RelationshipsPanel } from "./RelationshipsPanel";
import { SharingPanel } from "./SharingPanel";
import { ValidationRulesPanel } from "./ValidationRulesPanel";

type ObjectView = components["schemas"]["ObjectView"];
type ObjectSummary = components["schemas"]["ObjectSummaryView"];

interface Loaded {
  object: ObjectView;
  types: FieldTypeView[];
  objects: ObjectSummary[];
}

/**
 * One object of the organization (UA3, ADR-0170): it reads the object, the field types and the list of objects once, tells
 * the shell the object's name so the rail beside the page carries it, and shows the tab the address names
 * (`?section=fields`). Each tab is its own list with a page behind each row and a form behind New. The page shows what the
 * API answers and its words for every refusal; whether something may be changed is a flag the API sends (`editable`,
 * `extensible`), and the API checks it again on every call.
 */
export function ObjectFrame() {
  const params = useParams<{ objectApiName: string }>();
  const objectApiName = params.objectApiName;
  const section = sectionOf(useSearchParams()?.get("section"));
  const [loaded, setLoaded] = useState<Loaded | undefined>();
  const [failure, setFailure] = useState<string | undefined>();
  // Bumped every time a reload actually refreshes the object, so a tab that keeps its own copy of something (or reads
  // the fields of every object) knows to ask again instead of showing what it fetched at mount.
  const [refreshToken, setRefreshToken] = useState(0);
  const authorship = useAuthorship(objectApiName, refreshToken);

  const reload = useCallback(async () => {
    try {
      const one = await api.GET("/api/v1/metadata/objects/{objectApiName}", { params: { path: { objectApiName } } });
      if (!one.data) {
        setFailure(await failureText(one.error, one.response));
        return;
      }
      const types = await api.GET("/api/v1/metadata/field-types");
      const objects = await api.GET("/api/v1/metadata/objects");
      if (!types.data || !objects.data) {
        setFailure(await failureText(types.error ?? objects.error, types.data ? objects.response : types.response));
        return;
      }
      setFailure(undefined);
      setLoaded({ object: one.data.data, types: types.data.data, objects: objects.data.data });
      setRefreshToken((token) => token + 1);
    } catch {
      setFailure(COMMON_TEXT.network);
    }
  }, [objectApiName]);

  useEffect(() => {
    void Promise.resolve().then(reload);
  }, [reload]);

  usePublishOpenObject(objectApiName, loaded?.object.label);

  if (failure) {
    return (
      <p className="form-message" role="alert" data-testid="object-failed">
        {failure}
      </p>
    );
  }
  if (!loaded || loaded.object.apiName !== objectApiName) {
    return <p aria-live="polite">Loading…</p>;
  }

  const tab: ObjectTabProps = { ...loaded, reload, refreshToken, authorship };

  switch (section) {
    case "fields":
      return <FieldsPanel {...tab} />;
    case "relationships":
      return <RelationshipsPanel {...tab} />;
    case "record-types":
      return <RecordTypesPanel {...tab} />;
    case "rules":
      return <ValidationRulesPanel {...tab} />;
    case "layouts":
      return <LayoutsPanel {...tab} />;
    case "record-pages":
      return <RecordPagesPanel {...tab} />;
    case "sharing":
      return <SharingPanel {...tab} />;
    case "details":
      return <ObjectDetailsPanel {...tab} />;
  }
}
