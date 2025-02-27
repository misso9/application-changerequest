/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.changerequest.internal.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.changerequest.ChangeRequest;
import org.xwiki.contrib.changerequest.ChangeRequestConfiguration;
import org.xwiki.contrib.changerequest.ChangeRequestStatus;
import org.xwiki.contrib.changerequest.FileChange;
import org.xwiki.contrib.changerequest.internal.UserReferenceConverter;
import org.xwiki.contrib.changerequest.ChangeRequestException;
import org.xwiki.contrib.changerequest.internal.id.ChangeRequestIDGenerator;
import org.xwiki.contrib.changerequest.storage.ChangeRequestStorageManager;
import org.xwiki.contrib.changerequest.storage.FileChangeStorageManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.user.UserReferenceResolver;
import org.xwiki.user.UserReferenceSerializer;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Default implementation of {@link ChangeRequestStorageManager}.
 * The change request are stored as XWiki documents located on a dedicated space.
 *
 * @version $Id$
 * @since 0.1
 */
@Component
@Singleton
public class DefaultChangeRequestStorageManager implements ChangeRequestStorageManager
{
    static final LocalDocumentReference CHANGE_REQUEST_XCLASS =
        new LocalDocumentReference("ChangeRequest", "ChangeRequestClass");

    private static final String STATUS_PROPERTY = "status";
    private static final String CHANGED_DOCUMENTS_PROPERTY = "changedDocuments";
    private static final String REFERENCE = "reference";

    @Inject
    private UserReferenceConverter userReferenceConverter;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private FileChangeStorageManager fileChangeStorageManager;

    @Inject
    private DocumentReferenceResolver<ChangeRequest> changeRequestDocumentReferenceResolver;

    @Inject
    @Named("document")
    private UserReferenceResolver<DocumentReference> userReferenceResolver;

    @Inject
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    @Inject
    @Named("compactwiki")
    private EntityReferenceSerializer<String> localEntityReferenceSerializer;

    @Inject
    private DocumentReferenceResolver<String> documentReferenceResolver;

    @Inject
    private UserReferenceSerializer<String> userReferenceSerializer;

    @Inject
    @Named("title")
    private ChangeRequestIDGenerator idGenerator;

    @Inject
    private QueryManager queryManager;

    @Inject
    private ChangeRequestConfiguration configuration;

    @Override
    public void save(ChangeRequest changeRequest) throws ChangeRequestException
    {
        XWikiContext context = this.contextProvider.get();
        XWiki wiki = context.getWiki();
        if (changeRequest.getId() == null) {
            changeRequest.setId(this.idGenerator.generateId(changeRequest));
        }
        DocumentReference reference = this.changeRequestDocumentReferenceResolver.resolve(changeRequest);
        try {
            XWikiDocument document = wiki.getDocument(reference, context);
            document.setTitle(changeRequest.getTitle());
            document.setContent(changeRequest.getDescription());
            document.setContentAuthorReference(this.userReferenceConverter.convert(changeRequest.getCreator()));
            BaseObject xObject = document.getXObject(CHANGE_REQUEST_XCLASS, 0, true, context);
            xObject.set(STATUS_PROPERTY, changeRequest.getStatus().name().toLowerCase(Locale.ROOT), context);

            List<String> serializedReferences = changeRequest.getFileChanges().keySet().stream()
                .map(target -> this.localEntityReferenceSerializer.serialize(target))
                .collect(Collectors.toList());
            xObject.set(CHANGED_DOCUMENTS_PROPERTY, serializedReferences, context);

            List<String> serializedAuthors = changeRequest.getAuthors().stream()
                .map(target -> this.userReferenceSerializer.serialize(target))
                .collect(Collectors.toList());

            xObject.set("authors", serializedAuthors, context);

            wiki.saveDocument(document, context);
            for (FileChange fileChange : changeRequest.getAllFileChanges()) {
                this.fileChangeStorageManager.save(fileChange);
            }
        } catch (XWikiException e) {
            throw new ChangeRequestException(
                String.format("Error while saving the change request [%s]", changeRequest), e);
        }
    }

    @Override
    public Optional<ChangeRequest> load(String changeRequestId) throws ChangeRequestException
    {
        Optional<ChangeRequest> result = Optional.empty();
        ChangeRequest changeRequest = new ChangeRequest();
        changeRequest.setId(changeRequestId);
        DocumentReference reference = this.changeRequestDocumentReferenceResolver.resolve(changeRequest);
        XWikiContext context = this.contextProvider.get();
        XWiki wiki = context.getWiki();
        try {
            XWikiDocument document = wiki.getDocument(reference, context);
            BaseObject xObject = document.getXObject(CHANGE_REQUEST_XCLASS);
            if (!document.isNew() && xObject != null) {
                List<XWikiAttachment> attachmentList = document.getAttachmentList();
                List<FileChange> fileChanges = new ArrayList<>();
                ChangeRequestStatus status =
                    ChangeRequestStatus.valueOf(xObject.getStringValue(STATUS_PROPERTY).toUpperCase());
                changeRequest
                    .setTitle(document.getTitle())
                    .setDescription(document.getContent())
                    .setCreator(this.userReferenceResolver.resolve(document.getContentAuthorReference()))
                    .setStatus(status)
                    .setCreationDate(document.getCreationDate());

                for (XWikiAttachment attachment : attachmentList) {
                    String filename = attachment.getFilename();
                    if (filename.endsWith(".xml")) {
                        String filechangeId = filename.substring(0, filename.length() - 4);
                        Optional<FileChange> fileChange =
                            this.fileChangeStorageManager.load(changeRequest, filechangeId);
                        fileChange.ifPresent(changeRequest::addFileChange);
                    }
                }

                result = Optional.of(changeRequest);
            }
        } catch (XWikiException e) {
            throw new ChangeRequestException(
                String.format("Error while trying to load change request of id [%s]", changeRequestId), e);
        }
        return result;
    }

    @Override
    public void merge(ChangeRequest changeRequest) throws ChangeRequestException
    {
        // FIXME: we should maybe have a way to rollback if a merge has been only partially done?
        for (FileChange fileChange : changeRequest.getAllFileChanges()) {
            this.fileChangeStorageManager.merge(fileChange);
        }
        changeRequest.setStatus(ChangeRequestStatus.MERGED);
        this.save(changeRequest);
    }

    @Override
    public List<DocumentReference> getChangeRequestMatchingName(String title) throws ChangeRequestException
    {
        String statement = String.format("from doc.object(%s) as cr where doc.fullName like :reference",
            this.entityReferenceSerializer.serialize(CHANGE_REQUEST_XCLASS));
        SpaceReference changeRequestSpaceLocation = this.configuration.getChangeRequestSpaceLocation();
        try {
            Query query = this.queryManager.createQuery(statement, Query.XWQL);
            query.bindValue(REFERENCE, String.format("%s.%%%s%%",
                this.localEntityReferenceSerializer.serialize(changeRequestSpaceLocation), title));
            List<String> changeRequestDocuments = query.execute();
            return changeRequestDocuments.stream()
                .map(this.documentReferenceResolver::resolve).collect(Collectors.toList());
        } catch (QueryException e) {
            throw new ChangeRequestException(
                String.format("Error while looking for change requests with title [%s]", title), e);
        }
    }

    @Override
    public List<ChangeRequest> findChangeRequestTargeting(DocumentReference documentReference)
        throws ChangeRequestException
    {
        List<ChangeRequest> result = new ArrayList<>();
        SpaceReference changeRequestSpaceLocation = this.configuration.getChangeRequestSpaceLocation();
        String statement = String.format("where doc.space like :space and doc.object(%s).%s like :reference",
            this.entityReferenceSerializer.serialize(CHANGE_REQUEST_XCLASS), CHANGED_DOCUMENTS_PROPERTY);
        try {
            Query query = this.queryManager.createQuery(statement, Query.XWQL);
            query.bindValue("space", this.localEntityReferenceSerializer.serialize(changeRequestSpaceLocation));
            query.bindValue(REFERENCE, String.format("%%%s%%",
                this.localEntityReferenceSerializer.serialize(documentReference)));
            List<String> changeRequestDocuments = query.execute();
            for (String changeRequestDocument : changeRequestDocuments) {
                DocumentReference crReference = this.documentReferenceResolver.resolve(changeRequestDocument);
                this.load(crReference.getName()).ifPresent(result::add);
            }
        } catch (QueryException e) {
            throw new ChangeRequestException(
                String.format("Error while trying to get change request for document [%s]", documentReference), e);
        }

        return result;
    }
}
