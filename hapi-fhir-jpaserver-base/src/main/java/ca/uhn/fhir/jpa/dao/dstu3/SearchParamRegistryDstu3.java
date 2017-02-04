package ca.uhn.fhir.jpa.dao.dstu3;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang3.time.DateUtils;
import org.hl7.fhir.dstu3.model.CodeType;
import org.hl7.fhir.dstu3.model.SearchParameter;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.beans.factory.annotation.Autowired;

import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.context.RuntimeSearchParam.RuntimeSearchParamStatusEnum;
import ca.uhn.fhir.jpa.dao.BaseSearchParamRegistry;
import ca.uhn.fhir.jpa.dao.DaoConfig;
import ca.uhn.fhir.jpa.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.dao.SearchParameterMap;
import ca.uhn.fhir.jpa.util.StopWatch;
import ca.uhn.fhir.rest.method.RestSearchParameterTypeEnum;
import ca.uhn.fhir.rest.server.IBundleProvider;

public class SearchParamRegistryDstu3 extends BaseSearchParamRegistry {
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(SearchParamRegistryDstu3.class);

	@Autowired
	private IFhirResourceDao<SearchParameter> mySpDao;

	private long myLastRefresh;

	private volatile Map<String, Map<String, RuntimeSearchParam>> myActiveSearchParams;

	@Autowired
	private DaoConfig myDaoConfig;

	@Override
	public Map<String, RuntimeSearchParam> getActiveSearchParams(String theResourceName) {

		long refreshInterval = 60 * DateUtils.MILLIS_PER_MINUTE;
		if (System.currentTimeMillis() - refreshInterval > myLastRefresh) {
			StopWatch sw = new StopWatch();

			Map<String, Map<String, RuntimeSearchParam>> searchParams = new HashMap<String, Map<String, RuntimeSearchParam>>();
			for (Entry<String, Map<String, RuntimeSearchParam>> nextBuiltInEntry : getBuiltInSearchParams().entrySet()) {
				for (RuntimeSearchParam nextParam : nextBuiltInEntry.getValue().values()) {
					String nextResourceName = nextBuiltInEntry.getKey();
					getSearchParamMap(searchParams, nextResourceName).put(nextParam.getName(), nextParam);
				}
			}

			IBundleProvider allSearchParamsBp = mySpDao.search(new SearchParameterMap());
			int size = allSearchParamsBp.size();

			// Just in case..
			if (size > 10000) {
				ourLog.warn("Unable to support >10000 search params!");
				size = 10000;
			}

			List<IBaseResource> allSearchParams = allSearchParamsBp.getResources(0, size);
			for (IBaseResource nextResource : allSearchParams) {
				SearchParameter nextSp = (SearchParameter) nextResource;
				RuntimeSearchParam runtimeSp = toRuntimeSp(nextSp);
				if (runtimeSp == null) {
					continue;
				}
				
				int dotIdx = runtimeSp.getPath().indexOf('.');
				if (dotIdx == -1) {
					ourLog.warn("Can not determine resource type of {}", runtimeSp.getPath());
					continue;
				}
				String resourceType = runtimeSp.getPath().substring(0, dotIdx);

				Map<String, RuntimeSearchParam> searchParamMap = getSearchParamMap(searchParams, resourceType);
				String name = runtimeSp.getName();
				if (myDaoConfig.isDefaultSearchParamsCanBeOverridden() || !searchParamMap.containsKey(name)) {
					searchParamMap.put(name, runtimeSp);
				}
			}

			Map<String, Map<String, RuntimeSearchParam>> activeSearchParams = new HashMap<String, Map<String, RuntimeSearchParam>>();
			for (Entry<String, Map<String, RuntimeSearchParam>> nextEntry : searchParams.entrySet()) {
				for (RuntimeSearchParam nextSp : nextEntry.getValue().values()) {
					String nextName = nextSp.getName();
					if (nextSp.getStatus() != RuntimeSearchParamStatusEnum.ACTIVE) {
						nextSp = null;
					}
					
					if (!activeSearchParams.containsKey(nextEntry.getKey())) {
						activeSearchParams.put(nextEntry.getKey(), new HashMap<String, RuntimeSearchParam>());
					}
					if (activeSearchParams.containsKey(nextEntry.getKey())) {
						ourLog.info("Replacing existing/built in search param {}:{} with new one", nextEntry.getKey(), nextName);
					}
					
					if (nextSp != null) {
						activeSearchParams.get(nextEntry.getKey()).put(nextName, nextSp);
					} else {
						activeSearchParams.get(nextEntry.getKey()).remove(nextName);
					}
				}
			}

			myActiveSearchParams = activeSearchParams;

			myLastRefresh = System.currentTimeMillis();
			ourLog.info("Refreshed search parameter cache in {}ms", sw.getMillis());
		}

		return myActiveSearchParams.get(theResourceName);
	}

	@Override
	public void forceRefresh() {
		myLastRefresh = 0;
	}

	private Map<String, RuntimeSearchParam> getSearchParamMap(Map<String, Map<String, RuntimeSearchParam>> searchParams, String theResourceName) {
		Map<String, RuntimeSearchParam> retVal = searchParams.get(theResourceName);
		if (retVal == null) {
			retVal = new HashMap<String, RuntimeSearchParam>();
			searchParams.put(theResourceName, retVal);
		}
		return retVal;
	}

	private RuntimeSearchParam toRuntimeSp(SearchParameter theNextSp) {
		String name = theNextSp.getCode();
		String description = theNextSp.getDescription();
		String path = theNextSp.getXpath();
		RestSearchParameterTypeEnum paramType = null;
		RuntimeSearchParamStatusEnum status = null;
		switch (theNextSp.getType()) {
		case COMPOSITE:
			paramType = RestSearchParameterTypeEnum.COMPOSITE;
			break;
		case DATE:
			paramType = RestSearchParameterTypeEnum.DATE;
			break;
		case NUMBER:
			paramType = RestSearchParameterTypeEnum.NUMBER;
			break;
		case QUANTITY:
			paramType = RestSearchParameterTypeEnum.QUANTITY;
			break;
		case REFERENCE:
			paramType = RestSearchParameterTypeEnum.REFERENCE;
			break;
		case STRING:
			paramType = RestSearchParameterTypeEnum.STRING;
			break;
		case TOKEN:
			paramType = RestSearchParameterTypeEnum.TOKEN;
			break;
		case URI:
			paramType = RestSearchParameterTypeEnum.URI;
			break;
		case NULL:
			break;
		}
		if (theNextSp.getStatus() != null) {
			switch (theNextSp.getStatus()) {
			case ACTIVE:
				status = RuntimeSearchParamStatusEnum.ACTIVE;
				break;
			case DRAFT:
				status = RuntimeSearchParamStatusEnum.DRAFT;
				break;
			case RETIRED:
				status = RuntimeSearchParamStatusEnum.RETIRED;
				break;
			case NULL:
				break;
			}
		}
		Set<String> providesMembershipInCompartments = Collections.emptySet();
		Set<String> targets = toStrings(theNextSp.getTarget());

		if (isBlank(name) || isBlank(path) || paramType == null) {
			return null;
		}

		RuntimeSearchParam retVal = new RuntimeSearchParam(name, description, path, paramType, providesMembershipInCompartments, targets, status);
		return retVal;
	}

	private Set<String> toStrings(List<CodeType> theTarget) {
		HashSet<String> retVal = new HashSet<String>();
		for (CodeType next : theTarget) {
			if (isNotBlank(next.getValue())) {
				retVal.add(next.getValue());
			}
		}
		return retVal;
	}

}
